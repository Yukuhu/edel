package edel.lexer

import edel.fehler.Position

/**
 * Ein einzelnes Token aus dem Quelltext.
 *
 * @param literal der ausgewertete Literalwert (Long, Double, String oder Char),
 *                bei allen anderen Tokenarten `null`.
 */
data class Token(
    val typ: TokenTyp,
    val text: String,
    val position: Position,
    val literal: Any? = null,
) {
    override fun toString(): String = "$typ('$text')@$position"
}
