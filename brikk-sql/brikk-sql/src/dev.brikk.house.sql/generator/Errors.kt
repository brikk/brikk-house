package dev.brikk.house.sql.generator

// sqlglot: errors.UnsupportedError. Raised when the generator meets a node class it
// cannot render (Python raises ValueError there but funnels unsupported *features*
// through UnsupportedError; we use one type for both, carrying the class name).
class UnsupportedError(message: kotlin.String) : RuntimeException(message)
