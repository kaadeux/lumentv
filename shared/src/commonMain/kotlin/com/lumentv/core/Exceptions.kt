package com.lumentv.core

/** A server answered with an HTTP error status. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The content downloaded is not a playlist / guide we can read. */
class FormatException(message: String) : IOException(message)

/** Xtream Codes refused the login (auth = 0, expired or disabled account). */
class XtreamAuthException(message: String) : IOException(message)
