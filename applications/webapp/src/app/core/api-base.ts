/**
 * Base path for every request, including the media URLs bound straight into <video> and <img>
 * (those bypass the generated client, so they need the same prefix from somewhere).
 *
 * Relative on purpose: in production the build is served from the server's classpath:/static
 * under the /vidingest context path, and in development the dev server proxies /vidingest to
 * :8051. Same-origin either way, so no CORS preflight and no environment files.
 */
export const API_BASE = '/vidingest';
export const API_V1 = `${API_BASE}/api/v1`;

export const videoFileUrl = (videoId: string) => `${API_V1}/videos/${videoId}/file`;
export const frameImageUrl = (frameId: string) => `${API_V1}/frames/${frameId}/image`;
export const whisperTxtUrl = (videoId: string) => `${API_V1}/videos/${videoId}/transcription/whisper.txt`;
export const whisperJsonUrl = (videoId: string) => `${API_V1}/videos/${videoId}/transcription/whisper.json`;
