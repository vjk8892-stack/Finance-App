package dev.kosha.core.engine.merchant

/**
 * First-guess category for a merchant, by keyword.
 *
 * Spec G7 rule 4 learns a merchant's category from the user's own history —
 * three of the last four transactions agreeing. That rule is correct and stays
 * authoritative, but it cannot bootstrap: on a fresh install nothing has any
 * history, so EVERY transaction lands in Uncategorized. That in turn makes
 * every category-shaped visual degenerate to a single 100% slice, which is
 * useless to look at, and leaves the user hand-sorting hundreds of rows before
 * the app can say anything.
 *
 * So this fills the gap: a keyword guess, deliberately ranked BELOW the
 * learned rule. If the user recategorizes a merchant, their history wins from
 * then on and this never overrides it.
 *
 * Matching is on the normalized merchant string (see [MerchantMatcher]), so it
 * is case- and punctuation-insensitive and survives the "SWIGGY INSTAMART
 * PRI" style truncation banks apply.
 *
 * Category names must match the seeded set (spec G2) exactly — an unmatched
 * name is a no-op, not a crash.
 */
object MerchantCategoryRules {

    /**
     * Keyword → seeded category name. Longest keyword wins, so "swiggy
     * instamart" beats "swiggy" and lands in Groceries rather than Dining.
     */
    private val rules: List<Pair<String, String>> = listOf(
        // Groceries — checked before dining so the quick-commerce arms of
        // food brands do not all read as restaurants.
        "swiggy instamart" to "Groceries",
        "zepto" to "Groceries",
        "blinkit" to "Groceries",
        "bigbasket" to "Groceries",
        "dmart" to "Groceries",
        "d mart" to "Groceries",
        "reliance fresh" to "Groceries",
        "reliance smart" to "Groceries",
        "more retail" to "Groceries",
        "spencer" to "Groceries",
        "nature basket" to "Groceries",
        "licious" to "Groceries",
        "country delight" to "Groceries",
        "milk" to "Groceries",
        "kirana" to "Groceries",
        "supermarket" to "Groceries",
        "provision" to "Groceries",

        // Food & Dining
        "swiggy" to "Food & Dining",
        "zomato" to "Food & Dining",
        "eatsure" to "Food & Dining",
        "dominos" to "Food & Dining",
        "pizza" to "Food & Dining",
        "mcdonald" to "Food & Dining",
        "kfc" to "Food & Dining",
        "burger" to "Food & Dining",
        "subway" to "Food & Dining",
        "starbucks" to "Food & Dining",
        "cafe" to "Food & Dining",
        "coffee" to "Food & Dining",
        "restaurant" to "Food & Dining",
        "hotel" to "Food & Dining",
        "dhaba" to "Food & Dining",
        "bakery" to "Food & Dining",
        "sweets" to "Food & Dining",
        "biryani" to "Food & Dining",
        "canteen" to "Food & Dining",
        "cafeteria" to "Food & Dining",
        "food" to "Food & Dining",

        // Transport
        "uber" to "Transport",
        "ola" to "Transport",
        "rapido" to "Transport",
        "namma yatri" to "Transport",
        "bmtc" to "Transport",
        "metro" to "Transport",
        "irctc" to "Transport",
        "redbus" to "Transport",
        "fastag" to "Transport",
        "toll" to "Transport",
        "parking" to "Transport",

        // Fuel
        "indian oil" to "Fuel",
        "indianoil" to "Fuel",
        "bharat petroleum" to "Fuel",
        "hp petrol" to "Fuel",
        "hpcl" to "Fuel",
        "bpcl" to "Fuel",
        "shell" to "Fuel",
        "petrol" to "Fuel",
        "fuel" to "Fuel",
        "filling station" to "Fuel",

        // Shopping
        "amazon" to "Shopping",
        "flipkart" to "Shopping",
        "myntra" to "Shopping",
        "ajio" to "Shopping",
        "meesho" to "Shopping",
        "nykaa" to "Shopping",
        "tatacliq" to "Shopping",
        "decathlon" to "Shopping",
        "ikea" to "Shopping",
        "lifestyle" to "Shopping",
        "shoppers stop" to "Shopping",
        "westside" to "Shopping",
        "pantaloons" to "Shopping",
        "trends" to "Shopping",
        "croma" to "Shopping",
        "reliance digital" to "Shopping",

        // Bills & Utilities
        "electricity" to "Bills & Utilities",
        "bescom" to "Bills & Utilities",
        "mseb" to "Bills & Utilities",
        "tneb" to "Bills & Utilities",
        "gas" to "Bills & Utilities",
        "water board" to "Bills & Utilities",
        "airtel" to "Bills & Utilities",
        "jio" to "Bills & Utilities",
        "vodafone" to "Bills & Utilities",
        "vi postpaid" to "Bills & Utilities",
        "bsnl" to "Bills & Utilities",
        "act fibernet" to "Bills & Utilities",
        "broadband" to "Bills & Utilities",
        "recharge" to "Bills & Utilities",
        "dth" to "Bills & Utilities",
        "tata sky" to "Bills & Utilities",
        "tata play" to "Bills & Utilities",

        // Rent
        "rent" to "Rent",
        "landlord" to "Rent",
        "maintenance" to "Rent",
        "society" to "Rent",

        // EMI & Loans
        "emi" to "EMI & Loans",
        "loan" to "EMI & Loans",
        "bajaj finance" to "EMI & Loans",
        "hdb financial" to "EMI & Loans",
        "credit card payment" to "EMI & Loans",

        // Health
        "apollo" to "Health",
        "pharmeasy" to "Health",
        "1mg" to "Health",
        "netmeds" to "Health",
        "medplus" to "Health",
        "pharmacy" to "Health",
        "medical" to "Health",
        "hospital" to "Health",
        "clinic" to "Health",
        "diagnostic" to "Health",
        "lab" to "Health",
        "dental" to "Health",

        // Insurance
        "insurance" to "Insurance",
        "policybazaar" to "Insurance",
        "lic" to "Insurance",
        "premium" to "Insurance",

        // Education
        "school" to "Education",
        "college" to "Education",
        "tuition" to "Education",
        "udemy" to "Education",
        "coursera" to "Education",
        "unacademy" to "Education",
        "byju" to "Education",
        "vedantu" to "Education",

        // Entertainment
        "bookmyshow" to "Entertainment",
        "pvr" to "Entertainment",
        "inox" to "Entertainment",
        "cinema" to "Entertainment",
        "gaming" to "Entertainment",
        "steam" to "Entertainment",

        // Subscriptions
        "netflix" to "Subscriptions",
        "spotify" to "Subscriptions",
        "prime video" to "Subscriptions",
        "hotstar" to "Subscriptions",
        "sonyliv" to "Subscriptions",
        "zee5" to "Subscriptions",
        "youtube premium" to "Subscriptions",
        "google one" to "Subscriptions",
        "icloud" to "Subscriptions",
        "adobe" to "Subscriptions",
        "openai" to "Subscriptions",
        "subscription" to "Subscriptions",

        // Travel
        "makemytrip" to "Travel",
        "goibibo" to "Travel",
        "cleartrip" to "Travel",
        "yatra" to "Travel",
        "indigo" to "Travel",
        "air india" to "Travel",
        "vistara" to "Travel",
        "oyo" to "Travel",
        "airbnb" to "Travel",
        "booking com" to "Travel",

        // Personal Care
        "salon" to "Personal Care",
        "spa" to "Personal Care",
        "barber" to "Personal Care",
        "urban company" to "Personal Care",
        "gym" to "Personal Care",
        "fitness" to "Personal Care",
        "cult fit" to "Personal Care",

        // Construction & Home
        "cement" to "Construction & Home",
        "hardware" to "Construction & Home",
        "sanitary" to "Construction & Home",
        "plywood" to "Construction & Home",
        "tiles" to "Construction & Home",
        "paints" to "Construction & Home",
        "electrical" to "Construction & Home",
        "carpenter" to "Construction & Home",
        "plumber" to "Construction & Home",
    ).sortedByDescending { it.first.length }

    /** Income-side keywords, applied only to credits. */
    private val incomeRules: List<Pair<String, String>> = listOf(
        "salary" to "Salary",
        "payroll" to "Salary",
        "sal cr" to "Salary",
        "interest" to "Interest & Dividends",
        "dividend" to "Interest & Dividends",
        "refund" to "Refunds & Cashback",
        "cashback" to "Refunds & Cashback",
        "reversal" to "Refunds & Cashback",
    ).sortedByDescending { it.first.length }

    /**
     * Best-guess category name, or null when nothing is confident enough.
     *
     * [merchantNormalized] is expected to be [MerchantMatcher.normalize]d
     * output; anything else is normalized here so callers cannot get it wrong.
     */
    fun categoryNameFor(merchantNormalized: String?, isCredit: Boolean = false): String? {
        if (merchantNormalized.isNullOrBlank()) return null
        val haystack = MerchantMatcher.normalize(merchantNormalized).lowercase()
        if (haystack.isBlank()) return null

        val table = if (isCredit) incomeRules else rules
        return table.firstOrNull { (keyword, _) -> haystack.containsWord(keyword) }?.second
    }

    /**
     * Substring matching would map "LICIOUS" to Insurance via "lic" and
     * "OLA" to anything containing those letters. Require the keyword to sit
     * on token boundaries instead.
     */
    private fun String.containsWord(keyword: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(keyword, from)
            if (at < 0) return false
            val beforeOk = at == 0 || !this[at - 1].isLetterOrDigit()
            val end = at + keyword.length
            val afterOk = end == length || !this[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }
}
