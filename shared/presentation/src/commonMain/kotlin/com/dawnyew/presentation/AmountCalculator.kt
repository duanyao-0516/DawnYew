package com.dawnyew.presentation

import com.dawnyew.core.Money
import com.dawnyew.core.invalid

/** Converts the amount editor's expression to integer minor units. */
object AmountCalculator {
    fun evaluate(expression: String): Long {
        if (expression.length !in 1..96) invalid("算式需要 1–96 个字符")
        val value = Parser(expression).parse()
        if (value.numerator <= 0) invalid("记账金额必须大于 0")
        val whole = value.numerator / value.denominator
        if (whole > Money.MAX / 100) invalid("计算结果超过金额上限")
        var remainder = value.numerator % value.denominator
        // Long division avoids multiplying a possibly large remainder by 100.
        fun decimalDigit(): Int {
            var accumulated = 0L
            var digit = 0
            repeat(10) {
                if (accumulated >= value.denominator - remainder) {
                    accumulated -= value.denominator - remainder
                    digit++
                } else accumulated += remainder
            }
            remainder = accumulated
            return digit
        }
        var minor = whole * 100 + decimalDigit() * 10 + decimalDigit()
        if (remainder >= value.denominator - remainder) minor = Money.add(minor, 1)
        Money.requireValid(minor)
        return minor
    }

    private class Parser(private val text: String) {
        private var at = 0
        private var depth = 0

        fun parse(): Fraction {
            val result = sum()
            spaces()
            if (at != text.length) invalid("算式包含无效字符或缺少运算符")
            return result
        }

        private fun sum(): Fraction {
            var result = product()
            while (true) {
                spaces()
                result = when (peek()) {
                    '+' -> { at++; result + product() }
                    '-' -> { at++; result + product().negated() }
                    else -> return result
                }
            }
        }

        private fun product(): Fraction {
            var result = factor()
            while (true) {
                spaces()
                result = when (peek()) {
                    '*', '×' -> { at++; result * factor() }
                    '/', '÷' -> { at++; result / factor() }
                    else -> return result
                }
            }
        }

        private fun factor(): Fraction {
            spaces()
            if (++depth > 24) invalid("算式嵌套过深")
            val result = when (peek()) {
                '+' -> { at++; factor() }
                '-' -> { at++; factor().negated() }
                '(' -> {
                    at++
                    val nested = sum()
                    spaces()
                    if (peek() != ')') invalid("括号没有配对")
                    at++
                    nested
                }
                else -> number()
            }
            depth--
            return result
        }

        private fun number(): Fraction {
            val start = at
            while (peek() in '0'..'9') at++
            val integerDigits = at - start
            if (integerDigits !in 1..9) invalid("每个金额最多 9 位整数和 2 位小数")
            var fractionDigits = 0
            if (peek() == '.') {
                at++
                val fractionStart = at
                while (peek() in '0'..'9') at++
                fractionDigits = at - fractionStart
                if (fractionDigits > 2) invalid("每个金额最多 2 位小数")
            }
            val raw = text.substring(start, at)
            val numerator = raw.filterNot { it == '.' }.toLong()
            val denominator = when (fractionDigits) { 0 -> 1L; 1 -> 10L; else -> 100L }
            return Fraction.of(numerator, denominator)
        }

        private fun spaces() { while (at < text.length && text[at].isWhitespace()) at++ }
        private fun peek(): Char = text.getOrNull(at) ?: '\u0000'
    }

    /** Reduced rational arithmetic: no binary floating point and no rounding between operations. */
    private class Fraction private constructor(val numerator: Long, val denominator: Long) {
        fun negated() = Fraction(-numerator, denominator)

        operator fun plus(other: Fraction): Fraction {
            val shared = gcd(denominator, other.denominator)
            val n = Money.add(multiply(numerator, other.denominator / shared), multiply(other.numerator, denominator / shared))
            if (n == Long.MIN_VALUE) invalid("中间计算结果超出支持范围")
            val reduction = gcd(abs(n), shared)
            return of(n / reduction, multiply(denominator / shared, other.denominator / reduction))
        }

        operator fun times(other: Fraction): Fraction {
            val a = gcd(abs(numerator), other.denominator)
            val b = gcd(abs(other.numerator), denominator)
            return of(multiply(numerator / a, other.numerator / b), multiply(denominator / b, other.denominator / a))
        }

        operator fun div(other: Fraction): Fraction {
            if (other.numerator == 0L) invalid("不能除以 0")
            return this * of(if (other.numerator < 0) -other.denominator else other.denominator, abs(other.numerator))
        }

        companion object {
            fun of(n: Long, d: Long): Fraction {
                if (n == Long.MIN_VALUE || d <= 0) invalid("中间计算结果超出支持范围")
                val reduction = gcd(abs(n), d)
                return Fraction(n / reduction, d / reduction)
            }
            private fun abs(value: Long) = if (value < 0) -value else value
            private fun gcd(a: Long, b: Long): Long {
                var x = a
                var y = b
                while (y != 0L) { val rest = x % y; x = y; y = rest }
                return x
            }
            private fun multiply(a: Long, b: Long): Long {
                if (a == 0L || b == 0L) return 0
                if (abs(a) > Long.MAX_VALUE / abs(b)) invalid("中间计算结果超出支持范围，请缩短算式")
                return a * b
            }
        }
    }
}
