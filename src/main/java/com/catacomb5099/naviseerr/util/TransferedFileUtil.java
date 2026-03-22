package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;

import java.util.Arrays;
import java.util.List;

public class TransferedFileUtil {
    public static List<TransferState> getStateList(TransferedFile file) {
        return  Arrays.stream(file.getState().split(","))
                .map(String::trim)
                .map(state -> Arrays.stream(TransferState.values())
                        .filter(transferState -> transferState.name().equalsIgnoreCase(state))
                        .findFirst())
                .flatMap(java.util.Optional::stream)
                .toList();
    }
}
