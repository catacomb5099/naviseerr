Drop extra root CA certificates here as `*.pem` (gitignored) and they are imported into the build
JDK's truststore. Only needed behind a TLS-intercepting corporate proxy; on a normal network leave
this folder as it is.
