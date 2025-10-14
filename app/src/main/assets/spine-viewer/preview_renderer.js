// Global variables
let scene = getById('scene');
let colorInput = getById('colorInput');
let zoomInput = getById('zoom');
let speedInput = getById('speed');
let mixInput = getById('default-mix');
let skinList = getById('skins');
let animationList = getById('animations');
let alphaMode = 1; // Default to UNPACK
let availableAnimations = [];
let currentSpeed = 1;

// PIXI.js app setup
const app = new PIXI.Application({
    resizeTo: scene,
    antialias: true,
    autoDensity: true,
    transparent: true,
    preserveDrawingBuffer: true,
    resolution: window.devicePixelRatio || 1,
});
scene.appendChild(app.view);

// --- Operator Functions (from operator.js) ---
const setZoom = (scale) => {
    app.stage.children.forEach(a => {
        a.scale.x = a.scale.y = scale;
    });
};

const resetZoom = () => {
    setZoom(1);
    zoomInput.value = 100;
    getById('zoom-show').innerText = '100%';
};

const setSpeed = (speed) => {
    app.stage.children.forEach(a => {
        a.state.timeScale = speed;
    });
};

const resetSpeed = () => {
    setSpeed(1);
    speedInput.value = 1;
    getById('speed-show').innerText = '1.00x';
};

const setMix = (mix) => {
    app.stage.children.forEach(a => {
        a.state.data.defaultMix = mix;
    });
};

const resetMix = () => {
    setMix(0);
    mixInput.value = 0;
    getById('default-mix-show').innerText = '0.0s';
};

const setSkin = (skin) => {
    app.stage.children.forEach(a => {
        if (a.skeleton.data.skins.some(s => s.name === skin)) {
            a.skeleton.setSkinByName(skin);
            a.skeleton.setSlotsToSetupPose();
        }
    });
};

const resetPosition = () => {
    app.stage.children.forEach(a => a.position.set(scene.clientWidth / 2, scene.clientHeight / 2));
};

const playAnimation = (animation, loop) => {
    app.stage.children.forEach(a => {
        if (a.state) {
            a.state.timeScale = +speedInput.value;
            a.state.setAnimation(0, animation, loop);
        }
    });
};

const pauseAnimation = () => {
    const speed = speedInput.value;
    if (currentSpeed.toString() === speed) {
        setSpeed(0);
        speedInput.value = 0;
        getById('speed-show').innerText = '0.00x';
    } else {
        setSpeed(currentSpeed);
        speedInput.value = currentSpeed;
        getById('speed-show').innerText = currentSpeed.toFixed(2) + 'x';
    }
};

// --- Core Loading and Rendering Logic ---
function onLoaded(loader, res) {
    app.stage.removeChildren();
    availableAnimations = [];
    skinList.innerHTML = '';
    animationList.innerHTML = '';

    let spineResource = null;
    for (const key in res) {
        if (res[key] && res[key].spineData) {
            spineResource = res[key];
            break;
        }
    }

    if (!spineResource) {
        console.error("Spine data not found in loaded resources.");
        scene.innerHTML = '<div style="color: red; padding: 20px;">Error: Failed to load spine data.</div>';
        return;
    }

    for (const key in res) {
        if (res[key] && res[key].spineAtlas) {
            res[key].spineAtlas.pages.forEach(p => p.baseTexture.alphaMode = alphaMode);
        }
    }

    const skeleton = new PIXI.spine.Spine(spineResource.spineData);
    skeleton.position.set(app.view.width / 2, app.view.height / 2);

    const scale = +zoomInput.value / 100;
    skeleton.scale.set(scale, scale);
    skeleton.state.timeScale = +speedInput.value;
    skeleton.state.data.defaultMix = +mixInput.value;
    skeleton.autoUpdate = true;

    app.stage.addChild(skeleton);

    // Populate skins
    skeleton.spineData.skins.forEach((s, index) => {
        const li = createTag('li');
        const label = createTag('label');
        const input = createTag('input');
        label.setAttribute('for', `skin-${s.name}`);
        input.setAttribute('id', `skin-${s.name}`);
        input.setAttribute('value', s.name);
        input.setAttribute('type', 'radio');
        input.setAttribute('name', 'skin');
        input.addEventListener('change', (e) => setSkin(e.target.value));
        input.classList.add('list-option');
        if (index === 0) input.checked = true;
        label.innerHTML += s.name;
        li.append(input, label);
        skinList.append(li);
    });

    // Populate animations
    availableAnimations = skeleton.spineData.animations.map(a => ({ name: a.name, duration: a.duration.toFixed(3) }));
    availableAnimations.forEach((a, index) => {
        const li = createTag('li');
        const label = createTag('label');
        const span = createTag('span');
        const input = createTag('input');
        label.setAttribute('for', `animation-${a.name}`);
        input.setAttribute('id', `animation-${a.name}`);
        input.setAttribute('value', a.name);
        input.setAttribute('type', 'radio');
        input.setAttribute('name', 'animation');
        input.addEventListener('click', (e) => playAnimation(e.target.value, true));
        input.classList.add('list-option');
        if (index === 0) {
            input.checked = true;
            playAnimation(a.name, true);
        }
        span.innerText = a.duration + 's';
        label.innerHTML += a.name;
        label.append(span);
        li.append(input, label);
        animationList.append(li);
    });
}

// --- Event Listeners (from listener.js) ---
function setupEventListeners() {
    // Listen for background color changes
    colorInput.addEventListener('input', () => {
        app.renderer.backgroundColor = parseInt(colorInput.value.slice(1), 16);
    });

    // Listen for zoom slider changes
    zoomInput.addEventListener('input', () => {
        let scale = +zoomInput.value / 100;
        setZoom(scale);
        getById('zoom-show').innerText = zoomInput.value + '%';
    });

    // Listen for speed slider changes
    speedInput.addEventListener('input', () => {
        let speed = +speedInput.value;
        setSpeed(speed);
        currentSpeed = speed;
        getById('speed-show').innerText = (+speedInput.value).toFixed(2) + 'x';
    });

    // Listen for mix time slider changes
    mixInput.addEventListener('input', () => {
        let mix = +mixInput.value;
        setMix(mix);
        getById('default-mix-show').innerText = mix.toFixed(1) + 's';
    });

    // Listen for model dragging and zooming
    let isDragging = false;
    let lastPanPosition = { x: 0, y: 0 };
    const pointers = new Map();
    let prevDist = -1;

    const getDist = () => {
        const p = Array.from(pointers.values());
        const dx = p[0].x - p[1].x;
        const dy = p[0].y - p[1].y;
        return Math.sqrt(dx * dx + dy * dy);
    };

    const getMidpoint = () => {
        const p = Array.from(pointers.values());
        return {
            x: (p[0].x + p[1].x) / 2,
            y: (p[0].y + p[1].y) / 2
        };
    };

    app.view.addEventListener('pointerdown', (e) => {
        pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
        if (pointers.size === 1) {
            isDragging = true;
            lastPanPosition = { x: e.clientX, y: e.clientY };
        }
    });

    app.view.addEventListener('pointerup', (e) => {
        pointers.delete(e.pointerId);
        if (pointers.size < 2) prevDist = -1;
        if (pointers.size < 1) isDragging = false;
    });

    app.view.addEventListener('pointerout', (e) => {
        pointers.delete(e.pointerId);
        if (pointers.size < 2) prevDist = -1;
        if (pointers.size < 1) isDragging = false;
    });

    app.view.addEventListener('pointermove', (e) => {
        if (!pointers.has(e.pointerId)) return;
        pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

        const skeleton = app.stage.children[0];
        if (!skeleton) return;

        if (pointers.size === 2) {
            isDragging = false; // Disable panning when pinching
            const currentDist = getDist();
            const midpoint = getMidpoint();

            if (prevDist > 0) {
                const scaleChange = currentDist / prevDist;
                const oldScale = skeleton.scale.x;
                const newScale = Math.max(0.1, Math.min(oldScale * scaleChange, 5.0));

                skeleton.x = midpoint.x + (skeleton.x - midpoint.x) * (newScale / oldScale);
                skeleton.y = midpoint.y + (skeleton.y - midpoint.y) * (newScale / oldScale);
                skeleton.scale.set(newScale);

                zoomInput.value = newScale * 100;
                getById('zoom-show').innerText = Math.round(newScale * 100) + '%';
            }
            prevDist = currentDist;
        } else if (pointers.size === 1 && isDragging) {
            const panX = e.clientX - lastPanPosition.x;
            const panY = e.clientY - lastPanPosition.y;
            skeleton.x += panX;
            skeleton.y += panY;
            lastPanPosition = { x: e.clientX, y: e.clientY };
        }
    });

    // Panel Collapse Logic
    const mainPanel = getById('main');
    const toggleButton = getById('toggle-button');
    const updateButtonText = () => {
        toggleButton.innerText = mainPanel.classList.contains('side-collapsed') ? '‹' : '›';
    };
    toggleButton.addEventListener('click', () => {
        mainPanel.classList.toggle('side-collapsed');
        updateButtonText();
    });
    // Set initial state for toggle button text
    updateButtonText();
}

// --- Main Loading Logic ---
document.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    // Use decodeURIComponent to handle encoded file paths
    const skelPath = decodeURIComponent(params.get('skel') || '');
    const atlasPath = decodeURIComponent(params.get('atlas') || '');

    if (!skelPath || !atlasPath) {
        console.error('Missing skel or atlas path in URL parameters.');
        scene.innerHTML = '<div style="color: red; padding: 20px;">Error: Missing file paths. Cannot load model.</div>';
        return;
    }

    setupEventListeners();

    const atlasUrl = `file://${atlasPath}`;
    const skelUrl = `file://${skelPath}`;

    app.loader
        .add(atlasUrl) // Let PIXI use the URL as the key
        .add(skelUrl)
        .load(onLoaded);
});
