# -*- coding: utf-8 -*-
"""CDN access: bundle version discovery, catalog download, bundle download."""
import base64
import json
import os
import re
import struct
import tempfile
from pathlib import Path

import requests

import maintenance_info_pb2

MAINTENANCE_URL = 'https://mt.bd2.pmang.cloud/MaintenanceInfo'
CDN_ROOT = 'https://cdn.bd2.pmang.cloud/ServerData'


def _fetch_maintenance_info():
    """Fetch the maintenance protobuf. Uses Android's HttpURLConnection first
    because the endpoint's WAF rejects some TLS stacks (SSLEOFError)."""
    data_bytes = b'EAQ='
    response_bytes = None

    try:
        from java.net import URL
        url = URL(MAINTENANCE_URL)
        conn = url.openConnection()
        conn.setRequestMethod('PUT')
        conn.setRequestProperty('accept', '*/*')
        conn.setRequestProperty('accept-encoding', 'gzip')
        conn.setRequestProperty('connection', 'close')
        conn.setRequestProperty('content-type', 'multipart/form-data')
        conn.setRequestProperty('host', 'mt.bd2.pmang.cloud')
        conn.setRequestProperty(
            'user-agent',
            'UnityPlayer/2022.3.22f1 (UnityWebRequest/1.0, libcurl/8.5.0-DEV)'
        )
        conn.setDoOutput(True)
        out = conn.getOutputStream()
        out.write(data_bytes)
        out.flush()
        out.close()

        code = conn.getResponseCode()
        if code != 200:
            return None, 'MaintenanceInfo HTTP {}'.format(code)

        stream = conn.getInputStream()
        encoding = conn.getContentEncoding()
        if encoding and 'gzip' in encoding.lower():
            from java.util.zip import GZIPInputStream
            stream = GZIPInputStream(stream)
        from java.io import ByteArrayOutputStream
        buffer = ByteArrayOutputStream()
        chunk = bytearray(65536)
        while True:
            read = stream.read(chunk)
            if read == -1:
                break
            buffer.write(chunk, 0, read)
        stream.close()
        response_bytes = bytes(buffer.toByteArray())
    except ImportError:
        response_bytes = None
    except Exception as e:
        print('Native MaintenanceInfo request failed: {}'.format(e))

    if response_bytes is None:
        try:
            response = requests.put(
                MAINTENANCE_URL,
                headers={
                    'accept': '*/*',
                    'content-type': 'multipart/form-data',
                    'user-agent': 'UnityPlayer/2022.3.22f1 (UnityWebRequest/1.0, libcurl/8.5.0-DEV)',
                },
                data=data_bytes,
                timeout=30,
            )
            response.raise_for_status()
            response_bytes = response.content
        except requests.exceptions.RequestException as e:
            return None, 'Failed to reach MaintenanceInfo: {}'.format(e)

    try:
        payload = json.loads(response_bytes.decode('utf-8'))
        binary_data = base64.b64decode(payload['data'])
        maintenance_response = maintenance_info_pb2.MaintenanceInfoResponse()
        maintenance_response.ParseFromString(binary_data)
        return maintenance_response.market_info, None
    except Exception as e:
        return None, 'Failed to parse MaintenanceInfo: {}'.format(e)


def get_cdn_version(quality):
    """Return the current bundle version string for the requested quality."""
    market_info, error = _fetch_maintenance_info()
    if error:
        print(error)
        return None
    if quality == 'SD':
        return market_info.bundle_version_sd or market_info.bundle_version
    return market_info.bundle_version


def catalog_url(quality, version):
    return '{}/Android/{}/{}/catalog_alpha.json'.format(CDN_ROOT, quality, version)


def download_catalog(quality, version, progress_callback=None):
    """Download and parse the Addressables catalog. Returns (content, error).

    The response is streamed to a temporary file so the raw 60+ MB body is
    never held in memory twice on mobile devices.
    """
    url = catalog_url(quality, version)
    if progress_callback:
        progress_callback('Downloading game catalog ({})...'.format(version))

    tmp_path = None
    try:
        with requests.get(url, stream=True, timeout=60) as response:
            response.raise_for_status()
            fd, tmp_path = tempfile.mkstemp(suffix='.json')
            with os.fdopen(fd, 'wb') as handle:
                for chunk in response.iter_content(chunk_size=262144):
                    if chunk:
                        handle.write(chunk)
        with open(tmp_path, 'r', encoding='utf-8') as handle:
            content = json.load(handle)
        return content, None
    except requests.exceptions.RequestException as e:
        return None, 'Failed to download catalog: {}'.format(e)
    except ValueError as e:
        return None, 'Failed to parse catalog JSON: {}'.format(e)
    finally:
        if tmp_path:
            try:
                os.remove(tmp_path)
            except OSError:
                pass


def _try_download_bundle(url, output_file_path, bundle_size, progress_callback=None):
    try:
        with requests.get(url, stream=True, timeout=60) as response:
            response.raise_for_status()
            total = 0
            with open(output_file_path, 'wb') as handle:
                for chunk in response.iter_content(chunk_size=262144):
                    if chunk:
                        handle.write(chunk)
                        total += len(chunk)
                        if progress_callback:
                            progress_callback(
                                'Downloading... {:.1f} / {:.1f} MB'.format(
                                    total / 1048576.0, (bundle_size or 0) / 1048576.0))
        return True, None
    except requests.exceptions.RequestException as e:
        return False, str(e)


def generate_download_urls(base_url, internal_id, download_name, bundle_hash):
    """Build the ordered list of candidate CDN URLs for a bundle record."""
    urls = []

    if isinstance(internal_id, str) and internal_id:
        resolved = internal_id
        resolved = re.sub(
            r'\{BDNetwork\.CdnInfo\.Info\}',
            CDN_ROOT.rsplit('/ServerData', 1)[0] + '/ServerData',
            resolved, flags=re.IGNORECASE)
        resolved = re.sub(
            r'\{BDNetwork\.CdnInfo\.Resolution\}',
            base_url.rstrip('/').split('/Android/')[1].split('/')[0],
            resolved, flags=re.IGNORECASE)
        resolved = re.sub(
            r'\{BDNetwork\.CdnInfo\.Version\}',
            base_url.rstrip('/').split('/')[-1],
            resolved, flags=re.IGNORECASE)
        resolved = re.sub(
            r'\{UnityEngine\.AddressableAssets\.Addressables\.RuntimePath\}',
            base_url,
            resolved, flags=re.IGNORECASE)
        if '{' not in resolved:
            if resolved.startswith(('http://', 'https://')):
                urls.append(resolved)
            else:
                urls.append('{}/{}'.format(base_url, resolved.lstrip('/')))

    if isinstance(download_name, str) and download_name:
        urls.append('{}/{}'.format(base_url, download_name.lstrip('/')))

    # Content-hash suffixes are not consistent across CDN entries: some paths
    # include "_<hash>.bundle" and some do not, so both variants are tried.
    if isinstance(bundle_hash, str) and bundle_hash:
        for candidate in (internal_id, download_name):
            if not isinstance(candidate, str) or not candidate:
                continue
            if candidate.startswith(('{', 'http://', 'https://')):
                continue
            tail = candidate.rsplit('/', 1)[-1]
            if not tail.endswith('.bundle'):
                continue
            hash_suffix = '_{}.bundle'.format(bundle_hash)
            if tail.endswith(hash_suffix):
                urls.append('{}/{}'.format(base_url, candidate[:-len(hash_suffix)] + '.bundle'))
            else:
                urls.append('{}/{}'.format(base_url, candidate[:-7] + '_' + bundle_hash + '.bundle'))

    seen = set()
    ordered = []
    for url in urls:
        if url not in seen:
            seen.add(url)
            ordered.append(url)
    return ordered


def download_bundle(index, bundle_name, quality, version, output_dir, progress_callback=None):
    """Download one bundle using its catalog record.

    Returns (path_to___data, error).
    """
    record = ((index or {}).get('bundles') or {}).get(bundle_name)
    if not record:
        return None, 'Bundle {} is not present in the current game catalog.'.format(bundle_name)

    bundle_hash = record.get('hash') or ''
    base_url = '{}/Android/{}/{}'.format(CDN_ROOT, quality, version)
    urls = generate_download_urls(
        base_url, record.get('internal_id'), record.get('download_name'), bundle_hash)

    if not urls:
        return None, 'No CDN path known for bundle {}.'.format(bundle_name)

    output_file_path = Path(output_dir) / bundle_name / bundle_hash / '__data'
    output_file_path.parent.mkdir(parents=True, exist_ok=True)

    last_error = None
    for i, url in enumerate(urls):
        if progress_callback:
            if i == 0:
                progress_callback('Downloading bundle {} ({} MB)...'.format(
                    bundle_name, round((record.get('size') or 0) / 1048576.0, 1)))
            else:
                progress_callback('Retrying with alternative URL...')
        success, error = _try_download_bundle(
            url, str(output_file_path), record.get('size'), progress_callback)
        if success:
            return str(output_file_path), None
        last_error = error
        try:
            os.remove(str(output_file_path))
        except OSError:
            pass

    return None, 'Failed to download bundle {}: {}'.format(bundle_name, last_error)
