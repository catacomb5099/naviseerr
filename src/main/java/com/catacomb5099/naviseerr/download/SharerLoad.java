package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P9, rebuilt every pass by {@link DownloadTaskRunner}: which sharers are sending us one of our files
 * right now, and how many of our transfers each one holds. Built from our own {@code DOWNLOAD_POLL}
 * rows only, never from slskd's whole transfer list: that also holds the owner's manual downloads and
 * another install's, and letting those count would keep our rows waiting with no bound at all.
 *
 * @param delivering sharers with one of our transfers in progress and bytes moved
 * @param inFlight   our transfers per sharer; {@link #take} adds the ones this pass starts
 */
record SharerLoad(Set<String> delivering, Map<String, AtomicInteger> inFlight) {

    /**
     * @param slskdById slskd's view of our transfers, by id; empty when no transfer is polled this
     *                  pass, which is fine because only a poll reads {@code delivering}
     */
    static SharerLoad of(List<DownloadTaskRepository.TransferInFlight> ours,
                         Map<String, TransferedFile> slskdById) {
        Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Set<String> delivering = new HashSet<>();
        for (DownloadTaskRepository.TransferInFlight transfer : ours) {
            if (transfer.username() == null) {
                continue;
            }
            inFlight.computeIfAbsent(transfer.username(), sharer -> new AtomicInteger()).incrementAndGet();
            TransferedFile file = transfer.transferId() == null ? null : slskdById.get(transfer.transferId());
            if (file != null && DownloadStateMachine.isDelivering(file)) {
                delivering.add(transfer.username());
            }
        }
        return new SharerLoad(Set.copyOf(delivering), inFlight);
    }

    /**
     * Counts one more of our transfers with this sharer and returns how many it held before, so two
     * songs started in the same pass cannot both see a free place. A song that is then held back is
     * counted too; harmless, since once a sharer is full every later song of the pass is held anyway.
     */
    int take(String sharer) {
        return inFlight.computeIfAbsent(sharer, s -> new AtomicInteger()).getAndIncrement();
    }
}
