package com.vlessclient.service.mcp;

/**
 * Live traffic snapshot from the Clash API, returned by {@code get_traffic}.
 * Raw byte counters plus human-readable renderings so the agent can use either.
 *
 * @param uploadSpeedBytesPerSec   current upload rate in bytes/s
 * @param downloadSpeedBytesPerSec current download rate in bytes/s
 * @param totalUploadBytes         bytes uploaded this session
 * @param totalDownloadBytes       bytes downloaded this session
 * @param uploadSpeed              upload rate, formatted (e.g. "1.2 MB/s")
 * @param downloadSpeed            download rate, formatted
 * @param totalUpload              session upload, formatted (e.g. "340 MB")
 * @param totalDownload            session download, formatted
 */
public record TrafficInfo(
        long uploadSpeedBytesPerSec,
        long downloadSpeedBytesPerSec,
        long totalUploadBytes,
        long totalDownloadBytes,
        String uploadSpeed,
        String downloadSpeed,
        String totalUpload,
        String totalDownload) {
}
