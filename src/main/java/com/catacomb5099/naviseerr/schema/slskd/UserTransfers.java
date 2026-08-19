package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * One element of {@code GET /transfers/downloads}. That endpoint does NOT return a flat list of
 * transfers — it groups them by peer, then by directory, so the transfers themselves are two levels
 * down. Reading it as a flat {@code Flux<TransferedFile>} silently yields one all-null transfer per
 * peer (only {@code username} maps), which is what stranded every DOWNLOAD_POLL row: the lookup map
 * ended up keyed by null and no transfer id ever matched.
 *
 * <p>The per-transfer endpoint behind {@link com.catacomb5099.naviseerr.services.slskd.SlskdService}
 * {@code #getDownloadProgress} returns a bare {@link TransferedFile}; only the batched list is nested.
 */
@Getter
@AllArgsConstructor
public class UserTransfers {
    String username;
    List<TransferDirectory> directories;
}
