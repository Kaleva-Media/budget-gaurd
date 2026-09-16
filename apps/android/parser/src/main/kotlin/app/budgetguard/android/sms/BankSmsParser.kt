package app.budgetguard.android.sms

import java.time.LocalDate

class BankSmsParser(today: () -> LocalDate = LocalDate::now) {
    companion object {
        const val PARSER_VERSION = 3
    }

    private val parsers = listOf<(String) -> ParsedTransaction?>(
        AbsaSmsParser()::parse,
        FnbSmsParser(today)::parse,
    )

    fun parse(message: String): ParsedTransaction? = parsers.firstNotNullOfOrNull { parser -> parser(message) }
}
