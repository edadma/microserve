package io.github.edadma.microserve

/** Categorised bind failures, surfaced through `Server.listen`'s `onError`
  * callback (and `Server.bindWithRetry`'s typed retry).
  *
  * The four variants cover the cases dev-tool callers actually want to react
  * to differently: address-in-use is the only error that's worth retrying on a
  * neighbouring port; invalid-host means the user asked for something the OS
  * doesn't know about; permission-denied is what you get on Linux when binding
  * below 1024 without `CAP_NET_BIND_SERVICE`. Everything else falls into
  * `Other` — we don't try to enumerate exhaustively.
  *
  * `BindError` extends `RuntimeException` so existing handlers that just log
  * `e.getMessage` keep working unchanged after the upgrade. Callers that want
  * categorisation match on the variant.
  *
  * Each transport is responsible for translating its platform-specific error
  * (Java `BindException`, Node `e.code`, libuv error codes) before invoking
  * the user's `onError`.
  */
enum BindError(val cause: Throwable)
    extends RuntimeException(
      if cause != null && cause.getMessage != null then cause.getMessage else "bind failed",
      cause,
    ):
  case AddressInUse(c: Throwable)     extends BindError(c)
  case InvalidHost(c: Throwable)      extends BindError(c)
  case PermissionDenied(c: Throwable) extends BindError(c)
  case Other(c: Throwable)            extends BindError(c)
end BindError
