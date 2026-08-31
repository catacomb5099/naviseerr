package com.catacomb5099.naviseerr.download;

import java.util.List;

/** One page of the download history plus the total number of rows the page was cut from. Carries
 *  the raw count rather than a page count: turning it into pages is the controller's business,
 *  since only the request knows the page size. */
public record DownloadPage(List<ActiveDownloadView> downloads, long totalCount) {
}
