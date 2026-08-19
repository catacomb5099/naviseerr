package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** One directory's worth of transfers inside a {@link UserTransfers} group. */
@Getter
@AllArgsConstructor
public class TransferDirectory {
    String directory;
    Integer fileCount;
    List<TransferedFile> files;
}
