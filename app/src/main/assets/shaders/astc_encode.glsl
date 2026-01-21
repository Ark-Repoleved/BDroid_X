// ASTC 4x4 Encoder - OpenGL ES 3.1 Compute Shader
// Ported from niepp/astc_encoder (HLSL -> GLSL)
// Fixes: sRGB handling, Y-axis flip, Alpha channel support
#version 310 es
precision highp float;
precision highp int;

// Work group configuration
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// Uniforms
layout(binding = 0) uniform sampler2D u_inputTexture;
layout(std430, binding = 1) buffer OutputBuffer {
    uvec4 blocks[];
} u_output;

uniform int u_texelWidth;
uniform int u_texelHeight;
uniform int u_flipY;  // 1 = flip, 0 = no flip

// Constants
#define DIM 4
#define BLOCK_SIZE 16
#define X_GRIDS 4
#define Y_GRIDS 4
#define SMALL_VALUE 0.00001

// Color endpoint modes
#define CEM_LDR_RGB_DIRECT 8
#define CEM_LDR_RGBA_DIRECT 12

// Quantization ranges
#define QUANT_2 0
#define QUANT_3 1
#define QUANT_4 2
#define QUANT_5 3
#define QUANT_6 4
#define QUANT_8 5
#define QUANT_10 6
#define QUANT_12 7
#define QUANT_16 8
#define QUANT_20 9
#define QUANT_24 10
#define QUANT_32 11
#define QUANT_40 12
#define QUANT_48 13
#define QUANT_64 14
#define QUANT_80 15
#define QUANT_96 16
#define QUANT_128 17
#define QUANT_160 18
#define QUANT_192 19
#define QUANT_256 20
#define QUANT_MAX 21
#define WEIGHT_QUANTIZE_NUM 32

// ============================================================================
// ASTC_Table.hlsl data
// ============================================================================
const int scramble_table[12 * WEIGHT_QUANTIZE_NUM] = int[](
    // quantization method 0, range 0..1
    0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 1, range 0..2
    0, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 2, range 0..3
    0, 1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 3, range 0..4
    0, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 4, range 0..5
    0, 2, 4, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 5, range 0..7
    0, 1, 2, 3, 4, 5, 6, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 6, range 0..9
    0, 2, 4, 6, 8, 9, 7, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 7, range 0..11
    0, 4, 8, 2, 6, 10, 11, 7, 3, 9, 5, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 8, range 0..15
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 9, range 0..19
    0, 4, 8, 12, 16, 2, 6, 10, 14, 18, 19, 15, 11, 7, 3, 17, 13, 9, 5, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 10, range 0..23
    0, 8, 16, 2, 10, 18, 4, 12, 20, 6, 14, 22, 23, 15, 7, 21, 13, 5, 19, 11, 3, 17, 9, 1, 0, 0, 0, 0, 0, 0, 0, 0,
    // quantization method 11, range 0..31
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
);

// ============================================================================
// ASTC_IntegerSequenceEncoding.hlsl data
// ============================================================================
const int bits_trits_quints_table[QUANT_MAX * 3] = int[](
    1, 0, 0,  // RANGE_2
    0, 1, 0,  // RANGE_3
    2, 0, 0,  // RANGE_4
    0, 0, 1,  // RANGE_5
    1, 1, 0,  // RANGE_6
    3, 0, 0,  // RANGE_8
    1, 0, 1,  // RANGE_10
    2, 1, 0,  // RANGE_12
    4, 0, 0,  // RANGE_16
    2, 0, 1,  // RANGE_20
    3, 1, 0,  // RANGE_24
    5, 0, 0,  // RANGE_32
    3, 0, 1,  // RANGE_40
    4, 1, 0,  // RANGE_48
    6, 0, 0,  // RANGE_64
    4, 0, 1,  // RANGE_80
    5, 1, 0,  // RANGE_96
    7, 0, 0,  // RANGE_128
    5, 0, 1,  // RANGE_160
    6, 1, 0,  // RANGE_192
    8, 0, 0   // RANGE_256
);

const int integer_from_trits[243] = int[](
    0,1,2,    4,5,6,    8,9,10, 
    16,17,18, 20,21,22, 24,25,26,
    3,7,15,   19,23,27, 12,13,14, 
    32,33,34, 36,37,38, 40,41,42,
    48,49,50, 52,53,54, 56,57,58,
    35,39,47, 51,55,59, 44,45,46, 
    64,65,66, 68,69,70, 72,73,74,
    80,81,82, 84,85,86, 88,89,90,
    67,71,79, 83,87,91, 76,77,78,
    128,129,130, 132,133,134, 136,137,138,
    144,145,146, 148,149,150, 152,153,154,
    131,135,143, 147,151,155, 140,141,142,
    160,161,162, 164,165,166, 168,169,170,
    176,177,178, 180,181,182, 184,185,186,
    163,167,175, 179,183,187, 172,173,174,
    192,193,194, 196,197,198, 200,201,202,
    208,209,210, 212,213,214, 216,217,218,
    195,199,207, 211,215,219, 204,205,206,
    96,97,98,    100,101,102, 104,105,106,
    112,113,114, 116,117,118, 120,121,122,
    99,103,111,  115,119,123, 108,109,110, 
    224,225,226, 228,229,230, 232,233,234,
    240,241,242, 244,245,246, 248,249,250,
    227,231,239, 243,247,251, 236,237,238,
    28,29,30,    60,61,62,    92,93,94, 
    156,157,158, 188,189,190, 220,221,222,
    31,63,127,   159,191,255, 252,253,254
);

const int integer_from_quints[125] = int[](
    0,1,2,3,4,       8,9,10,11,12,      16,17,18,19,20,    24,25,26,27,28,     5,13,21,29,6,
    32,33,34,35,36,  40,41,42,43,44,    48,49,50,51,52,    56,57,58,59,60,     37,45,53,61,14,
    64,65,66,67,68,  72,73,74,75,76,    80,81,82,83,84,    88,89,90,91,92,     69,77,85,93,22,
    96,97,98,99,100, 104,105,106,107,108, 112,113,114,115,116, 120,121,122,123,124, 101,109,117,125,30,
    102,103,70,71,38, 110,111,78,79,46,  118,119,86,87,54,  126,127,94,95,62,   39,47,55,63,31
);

// ============================================================================
// Bit manipulation functions
// ============================================================================
uint reverse_byte(uint p) {
    p = ((p & 0xFu) << 4) | ((p >> 4) & 0xFu);
    p = ((p & 0x33u) << 2) | ((p >> 2) & 0x33u);
    p = ((p & 0x55u) << 1) | ((p >> 1) & 0x55u);
    return p;
}

void orbits8_ptr(inout uvec4 outputs, inout uint bitoffset, uint number, uint bitcount) {
    uint newpos = bitoffset + bitcount;
    uint nidx = newpos >> 5u;
    uint uidx = bitoffset >> 5u;
    uint bit_idx = bitoffset & 31u;

    uint bytes[4] = uint[](outputs.x, outputs.y, outputs.z, outputs.w);
    bytes[uidx] |= (number << bit_idx);
    if (nidx > uidx && uidx + 1u < 4u) {
        bytes[uidx + 1u] |= (number >> (32u - bit_idx));
    }

    outputs.x = bytes[0];
    outputs.y = bytes[1];
    outputs.z = bytes[2];
    outputs.w = bytes[3];
    bitoffset = newpos;
}

void split_high_low(uint n, uint i, out int high, out uint low) {
    uint low_mask = (1u << i) - 1u;
    low = n & low_mask;
    high = int((n >> i) & 0xFFu);
}

// ============================================================================
// ISE encoding functions
// ============================================================================
void encode_trits(uint bitcount, uint b0, uint b1, uint b2, uint b3, uint b4,
                  inout uvec4 outputs, inout uint outpos) {
    int t0, t1, t2, t3, t4;
    uint m0, m1, m2, m3, m4;

    split_high_low(b0, bitcount, t0, m0);
    split_high_low(b1, bitcount, t1, m1);
    split_high_low(b2, bitcount, t2, m2);
    split_high_low(b3, bitcount, t3, m3);
    split_high_low(b4, bitcount, t4, m4);

    uint packhigh = uint(integer_from_trits[t4 * 81 + t3 * 27 + t2 * 9 + t1 * 3 + t0]);

    orbits8_ptr(outputs, outpos, m0, bitcount);
    orbits8_ptr(outputs, outpos, packhigh & 3u, 2u);
    orbits8_ptr(outputs, outpos, m1, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 2) & 3u, 2u);
    orbits8_ptr(outputs, outpos, m2, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 4) & 1u, 1u);
    orbits8_ptr(outputs, outpos, m3, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 5) & 3u, 2u);
    orbits8_ptr(outputs, outpos, m4, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 7) & 1u, 1u);
}

void encode_quints(uint bitcount, uint b0, uint b1, uint b2,
                   inout uvec4 outputs, inout uint outpos) {
    int q0, q1, q2;
    uint m0, m1, m2;

    split_high_low(b0, bitcount, q0, m0);
    split_high_low(b1, bitcount, q1, m1);
    split_high_low(b2, bitcount, q2, m2);

    uint packhigh = uint(integer_from_quints[q2 * 25 + q1 * 5 + q0]);

    orbits8_ptr(outputs, outpos, m0, bitcount);
    orbits8_ptr(outputs, outpos, packhigh & 7u, 3u);
    orbits8_ptr(outputs, outpos, m1, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 3) & 3u, 2u);
    orbits8_ptr(outputs, outpos, m2, bitcount);
    orbits8_ptr(outputs, outpos, (packhigh >> 5) & 3u, 2u);
}

void bise_endpoints(uint numbers[8], int range, inout uvec4 outputs) {
    uint bitpos = 0u;
    uint bits = uint(bits_trits_quints_table[range * 3 + 0]);
    uint trits = uint(bits_trits_quints_table[range * 3 + 1]);
    uint quints = uint(bits_trits_quints_table[range * 3 + 2]);
    int count = 8;  // HAS_ALPHA = 1

    if (trits == 1u) {
        encode_trits(bits, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], outputs, bitpos);
        encode_trits(bits, numbers[5], numbers[6], numbers[7], 0u, 0u, outputs, bitpos);
    } else if (quints == 1u) {
        encode_quints(bits, numbers[0], numbers[1], numbers[2], outputs, bitpos);
        encode_quints(bits, numbers[3], numbers[4], numbers[5], outputs, bitpos);
        encode_quints(bits, numbers[6], numbers[7], 0u, outputs, bitpos);
    } else {
        for (int i = 0; i < count; ++i) {
            orbits8_ptr(outputs, bitpos, numbers[i], bits);
        }
    }
}

void bise_weights(uint numbers[16], int range, inout uvec4 outputs) {
    uint bitpos = 0u;
    uint bits = uint(bits_trits_quints_table[range * 3 + 0]);
    uint trits = uint(bits_trits_quints_table[range * 3 + 1]);
    uint quints = uint(bits_trits_quints_table[range * 3 + 2]);

    if (trits == 1u) {
        encode_trits(bits, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], outputs, bitpos);
        encode_trits(bits, numbers[5], numbers[6], numbers[7], numbers[8], numbers[9], outputs, bitpos);
        encode_trits(bits, numbers[10], numbers[11], numbers[12], numbers[13], numbers[14], outputs, bitpos);
        encode_trits(bits, numbers[15], 0u, 0u, 0u, 0u, outputs, bitpos);
    } else if (quints == 1u) {
        encode_quints(bits, numbers[0], numbers[1], numbers[2], outputs, bitpos);
        encode_quints(bits, numbers[3], numbers[4], numbers[5], outputs, bitpos);
        encode_quints(bits, numbers[6], numbers[7], numbers[8], outputs, bitpos);
        encode_quints(bits, numbers[9], numbers[10], numbers[11], outputs, bitpos);
        encode_quints(bits, numbers[12], numbers[13], numbers[14], outputs, bitpos);
        encode_quints(bits, numbers[15], 0u, 0u, outputs, bitpos);
    } else {
        for (int i = 0; i < 16; ++i) {
            orbits8_ptr(outputs, bitpos, numbers[i], bits);
        }
    }
}

// ============================================================================
// PCA and endpoint calculation
// ============================================================================
vec4 eigen_vector(mat4 m) {
    vec4 v = vec4(0.26726, 0.80178, 0.53452, 0.0);
    for (int i = 0; i < 8; ++i) {
        v = m * v;
        if (length(v) < SMALL_VALUE) {
            return v;
        }
        v = normalize(m * v);
    }
    return v;
}

void find_min_max(vec4 texels[BLOCK_SIZE], vec4 pt_mean, vec4 vec_k, out vec4 e0, out vec4 e1) {
    float a = 1e31;
    float b = -1e31;
    for (int i = 0; i < BLOCK_SIZE; ++i) {
        vec4 texel = texels[i] - pt_mean;
        float t = dot(texel, vec_k);
        a = min(a, t);
        b = max(b, t);
    }

    e0 = clamp(vec_k * a + pt_mean, 0.0, 255.0);
    e1 = clamp(vec_k * b + pt_mean, 0.0, 255.0);

    // Ensure first endpoint is darkest
    vec4 e0u = round(e0);
    vec4 e1u = round(e1);
    if (e0u.x + e0u.y + e0u.z > e1u.x + e1u.y + e1u.z) {
        vec4 tmp = e0;
        e0 = e1;
        e1 = tmp;
    }
}

void principal_component_analysis(vec4 texels[BLOCK_SIZE], out vec4 e0, out vec4 e1) {
    vec4 pt_mean = vec4(0.0);
    for (int i = 0; i < BLOCK_SIZE; ++i) {
        pt_mean += texels[i];
    }
    pt_mean /= float(BLOCK_SIZE);

    mat4 cov = mat4(0.0);
    for (int k = 0; k < BLOCK_SIZE; ++k) {
        vec4 texel = texels[k] - pt_mean;
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) {
                cov[i][j] += texel[i] * texel[j];
            }
        }
    }
    cov /= float(BLOCK_SIZE - 1);

    vec4 vec_k = eigen_vector(cov);
    find_min_max(texels, pt_mean, vec_k, e0, e1);
}

// ============================================================================
// Weight calculation
// ============================================================================
void calculate_quantized_weights(vec4 texels[BLOCK_SIZE], uint weight_range, vec4 ep0, vec4 ep1, out uint weights[16]) {
    vec4 vec_k = ep1 - ep0;
    float projw[16];
    
    if (length(vec_k) < SMALL_VALUE) {
        for (int i = 0; i < 16; ++i) {
            projw[i] = 0.0;
        }
    } else {
        vec_k = normalize(vec_k);
        float minw = 1e31;
        float maxw = -1e31;
        
        for (int i = 0; i < BLOCK_SIZE; ++i) {
            float w = dot(vec_k, texels[i] - ep0);
            minw = min(w, minw);
            maxw = max(w, maxw);
            projw[i] = w;
        }

        float invlen = maxw - minw;
        invlen = max(SMALL_VALUE, invlen);
        invlen = 1.0 / invlen;
        for (int i = 0; i < 16; ++i) {
            projw[i] = (projw[i] - minw) * invlen;
        }
    }
    
    for (int i = 0; i < 16; ++i) {
        uint q = uint(round(projw[i] * float(weight_range)));
        weights[i] = clamp(q, 0u, weight_range);
    }
}

// ============================================================================
// Block encoding
// ============================================================================
void encode_color(vec4 e0, vec4 e1, out uint endpoint_quantized[8]) {
    uvec4 e0q = uvec4(round(e0));
    uvec4 e1q = uvec4(round(e1));
    endpoint_quantized[0] = e0q.r;
    endpoint_quantized[1] = e1q.r;
    endpoint_quantized[2] = e0q.g;
    endpoint_quantized[3] = e1q.g;
    endpoint_quantized[4] = e0q.b;
    endpoint_quantized[5] = e1q.b;
    endpoint_quantized[6] = e0q.a;
    endpoint_quantized[7] = e1q.a;
}

uint assemble_blockmode(uint weight_quantmethod) {
    uint a = uint(Y_GRIDS - 2) & 0x3u;
    uint b = uint(X_GRIDS - 4) & 0x3u;
    uint d = 0u;  // dual plane
    uint h = (weight_quantmethod < 6u) ? 0u : 1u;
    uint r = (weight_quantmethod % 6u) + 2u;

    uint blockmode = (r >> 1) & 0x3u;
    blockmode |= (r & 0x1u) << 4;
    blockmode |= (a & 0x3u) << 5;
    blockmode |= (b & 0x3u) << 7;
    blockmode |= h << 9;
    blockmode |= d << 10;
    return blockmode;
}

uvec4 assemble_block(uint blockmode, uint color_endpoint_mode, uvec4 ep_ise, uvec4 wt_ise) {
    uvec4 phy_blk = uvec4(0u);
    
    // weights ise
    phy_blk.w |= reverse_byte(wt_ise.x & 0xFFu) << 24;
    phy_blk.w |= reverse_byte((wt_ise.x >> 8) & 0xFFu) << 16;
    phy_blk.w |= reverse_byte((wt_ise.x >> 16) & 0xFFu) << 8;
    phy_blk.w |= reverse_byte((wt_ise.x >> 24) & 0xFFu);

    phy_blk.z |= reverse_byte(wt_ise.y & 0xFFu) << 24;
    phy_blk.z |= reverse_byte((wt_ise.y >> 8) & 0xFFu) << 16;
    phy_blk.z |= reverse_byte((wt_ise.y >> 16) & 0xFFu) << 8;
    phy_blk.z |= reverse_byte((wt_ise.y >> 24) & 0xFFu);

    phy_blk.y |= reverse_byte(wt_ise.z & 0xFFu) << 24;
    phy_blk.y |= reverse_byte((wt_ise.z >> 8) & 0xFFu) << 16;
    phy_blk.y |= reverse_byte((wt_ise.z >> 16) & 0xFFu) << 8;
    phy_blk.y |= reverse_byte((wt_ise.z >> 24) & 0xFFu);

    // blockmode & cem
    phy_blk.x = blockmode;
    phy_blk.x |= (color_endpoint_mode & 0xFu) << 13;

    // endpoints
    phy_blk.x |= (ep_ise.x & 0x7FFFu) << 17;
    phy_blk.y = ((ep_ise.x >> 15) & 0x1FFFFu);
    phy_blk.y |= (ep_ise.y & 0x7FFFu) << 17;
    phy_blk.z |= ((ep_ise.y >> 15) & 0x1FFFFu);

    return phy_blk;
}

// Calculate error for a given endpoint pair
float calculate_block_error(vec4 texels[BLOCK_SIZE], vec4 ep0, vec4 ep1, uint weight_range) {
    vec4 vec_k = ep1 - ep0;
    float error = 0.0;
    
    if (length(vec_k) < SMALL_VALUE) {
        // Both endpoints same, error is distance from mean
        for (int i = 0; i < BLOCK_SIZE; ++i) {
            vec4 diff = texels[i] - ep0;
            error += dot(diff, diff);
        }
        return error;
    }
    
    vec_k = normalize(vec_k);
    float len = length(ep1 - ep0);
    
    for (int i = 0; i < BLOCK_SIZE; ++i) {
        // Project texel onto line
        float t = dot(texels[i] - ep0, vec_k);
        t = clamp(t, 0.0, len);
        
        // Quantize weight
        float w = t / max(len, SMALL_VALUE);
        uint qw = uint(round(w * float(weight_range)));
        qw = min(qw, weight_range);
        
        // Reconstruct color
        float dw = float(qw) / float(weight_range);
        vec4 reconstructed = mix(ep0, ep1, dw);
        
        // Calculate error
        vec4 diff = texels[i] - reconstructed;
        error += dot(diff, diff);
    }
    
    return error;
}

// Find min/max endpoints (alternative to PCA)
void find_minmax_endpoints(vec4 texels[BLOCK_SIZE], out vec4 e0, out vec4 e1) {
    vec4 minVal = vec4(255.0);
    vec4 maxVal = vec4(0.0);
    
    for (int i = 0; i < BLOCK_SIZE; ++i) {
        minVal = min(minVal, texels[i]);
        maxVal = max(maxVal, texels[i]);
    }
    
    e0 = minVal;
    e1 = maxVal;
}

// Find luminance-based endpoints
void find_luma_endpoints(vec4 texels[BLOCK_SIZE], out vec4 e0, out vec4 e1) {
    float minLuma = 1e31;
    float maxLuma = -1e31;
    int minIdx = 0;
    int maxIdx = 0;
    
    for (int i = 0; i < BLOCK_SIZE; ++i) {
        float luma = dot(texels[i].rgb, vec3(0.299, 0.587, 0.114));
        if (luma < minLuma) {
            minLuma = luma;
            minIdx = i;
        }
        if (luma > maxLuma) {
            maxLuma = luma;
            maxIdx = i;
        }
    }
    
    e0 = texels[minIdx];
    e1 = texels[maxIdx];
}

uvec4 encode_block(vec4 texels[BLOCK_SIZE]) {
    // Try multiple endpoint strategies and pick the best one
    vec4 ep0_pca, ep1_pca;
    vec4 ep0_minmax, ep1_minmax;
    vec4 ep0_luma, ep1_luma;
    
    principal_component_analysis(texels, ep0_pca, ep1_pca);
    find_minmax_endpoints(texels, ep0_minmax, ep1_minmax);
    find_luma_endpoints(texels, ep0_luma, ep1_luma);
    
    // QUANT_6 = 6 levels (0-5), compatible with blockmode encoding
    // Note: QUANT_12 causes blockmode encoding issues
    uint weight_quantmethod = uint(QUANT_6);
    uint endpoint_quantmethod = uint(QUANT_256);
    uint weight_range = 6u;
    
    // Calculate error for each strategy
    float error_pca = calculate_block_error(texels, ep0_pca, ep1_pca, weight_range - 1u);
    float error_minmax = calculate_block_error(texels, ep0_minmax, ep1_minmax, weight_range - 1u);
    float error_luma = calculate_block_error(texels, ep0_luma, ep1_luma, weight_range - 1u);
    
    // Pick best endpoints
    vec4 ep0, ep1;
    if (error_pca <= error_minmax && error_pca <= error_luma) {
        ep0 = ep0_pca;
        ep1 = ep1_pca;
    } else if (error_minmax <= error_luma) {
        ep0 = ep0_minmax;
        ep1 = ep1_minmax;
    } else {
        ep0 = ep0_luma;
        ep1 = ep1_luma;
    }
    
    // Ensure first endpoint is darkest (for consistency)
    vec4 e0u = round(ep0);
    vec4 e1u = round(ep1);
    if (e0u.x + e0u.y + e0u.z > e1u.x + e1u.y + e1u.z) {
        vec4 tmp = ep0;
        ep0 = ep1;
        ep1 = tmp;
    }

    uint blockmode = assemble_blockmode(weight_quantmethod);

    // Encode endpoints
    uint ep_quantized[8];
    encode_color(ep0, ep1, ep_quantized);
    uvec4 ep_ise = uvec4(0u);
    bise_endpoints(ep_quantized, int(endpoint_quantmethod), ep_ise);

    // Encode weights with higher precision
    uint wt_quantized[16];
    calculate_quantized_weights(texels, weight_range - 1u, ep0, ep1, wt_quantized);
    for (int i = 0; i < 16; ++i) {
        int w = int(weight_quantmethod) * WEIGHT_QUANTIZE_NUM + int(wt_quantized[i]);
        wt_quantized[i] = uint(scramble_table[w]);
    }
    uvec4 wt_ise = uvec4(0u);
    bise_weights(wt_quantized, int(weight_quantmethod), wt_ise);

    uint color_endpoint_mode = uint(CEM_LDR_RGBA_DIRECT);
    return assemble_block(blockmode, color_endpoint_mode, ep_ise, wt_ise);
}

// ============================================================================
// Main compute shader entry point
// ============================================================================
void main() {
    uint blockID = gl_GlobalInvocationID.y * uint((u_texelWidth + DIM - 1) / DIM) + gl_GlobalInvocationID.x;
    uint blockNumX = uint((u_texelWidth + DIM - 1) / DIM);
    uint blockNumY = uint((u_texelHeight + DIM - 1) / DIM);
    
    // Bounds check
    if (gl_GlobalInvocationID.x >= blockNumX || gl_GlobalInvocationID.y >= blockNumY) {
        return;
    }

    vec4 texels[BLOCK_SIZE];
    uvec2 blockPos = uvec2(gl_GlobalInvocationID.x, gl_GlobalInvocationID.y);
    
    for (int k = 0; k < BLOCK_SIZE; ++k) {
        uint y = uint(k) / uint(DIM);
        uint x = uint(k) - y * uint(DIM);
        
        ivec2 pixelPos = ivec2(blockPos) * DIM + ivec2(x, y);
        
        // Clamp to texture bounds
        pixelPos.x = min(pixelPos.x, u_texelWidth - 1);
        pixelPos.y = min(pixelPos.y, u_texelHeight - 1);
        
        // Apply Y-axis flip if needed (for Unity/OpenGL coordinate system)
        if (u_flipY == 1) {
            pixelPos.y = u_texelHeight - 1 - pixelPos.y;
        }
        
        // Sample texture (already in 0-1 range, convert to 0-255)
        vec4 texel = texelFetch(u_inputTexture, pixelPos, 0);
        texels[k] = texel * 255.0;
    }
    
    u_output.blocks[blockID] = encode_block(texels);
}
