package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class QueueDownloadResponse {
    List<TransferedFile> enqueued;
    List<TransferedFile> failed;
}
