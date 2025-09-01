package app.ui.main.fragments.settings.dialogs

/**
 * Provides a predefined list of regions (countries) represented as ISO country codes
 * along with their corresponding flag emojis and localized names.
 *
 * This list is intended for use in application settings or dialogs where a user
 * needs to select a country or region.
 *
 * Example entry:
 * ```
 * "US" to "🇺🇸 United States"
 * ```
 *
 * - The first value (`String`) in the pair is the ISO 3166-1 alpha-2 country code.
 * - The second value (`String`) combines the country’s emoji flag with its name.
 *
 * Usage:
 * ```
 * val regions = ContentRegionsList.regionsList
 * regions.forEach { (code, name) ->
 *     println("Code: $code, Display Name: $name")
 * }
 * ```
 */
object ContentRegionsList {

	/**
	 * A static list of supported regions represented as a list of key-value pairs.
	 *
	 * - **Key**: ISO 3166-1 alpha-2 country code (e.g., `"US"`, `"IN"`).
	 * - **Value**: Display name containing an emoji flag and the full country name
	 *   (e.g., `"🇺🇸 United States"`, `"🇮🇳 India"`).
	 */
	val regionsList: List<Pair<String, String>> = listOf(
		"US" to "🇺🇸 United States",
		"IN" to "🇮🇳 India",
		"JP" to "🇯🇵 Japan",
		"CN" to "🇨🇳 China",
		"KR" to "🇰🇷 South Korea",
		"RU" to "🇷🇺 Russia",
		"BR" to "🇧🇷 Brazil",
		"MX" to "🇲🇽 Mexico",
		"CA" to "🇨🇦 Canada",
		"GB" to "🇬🇧 United Kingdom",
		"DE" to "🇩🇪 Germany",
		"FR" to "🇫🇷 France",
		"IT" to "🇮🇹 Italy",
		"ES" to "🇪🇸 Spain",
		"AU" to "🇦🇺 Australia",
		"NZ" to "🇳🇿 New Zealand",
		"ZA" to "🇿🇦 South Africa",
		"NG" to "🇳🇬 Nigeria",
		"EG" to "🇪🇬 Egypt",
		"SA" to "🇸🇦 Saudi Arabia",
		"AE" to "🇦🇪 United Arab Emirates",
		"TR" to "🇹🇷 Turkey",
		"IR" to "🇮🇷 Iran",
		"PK" to "🇵🇰 Pakistan",
		"BD" to "🇧🇩 Bangladesh",
		"LK" to "🇱🇰 Sri Lanka",
		"NP" to "🇳🇵 Nepal",
		"TH" to "🇹🇭 Thailand",
		"VN" to "🇻🇳 Vietnam",
		"PH" to "🇵🇭 Philippines",
		"ID" to "🇮🇩 Indonesia",
		"MY" to "🇲🇾 Malaysia",
		"SG" to "🇸🇬 Singapore",
		"HK" to "🇭🇰 Hong Kong",
		"TW" to "🇹🇼 Taiwan",
		"IL" to "🇮🇱 Israel",
		"KE" to "🇰🇪 Kenya",
		"TZ" to "🇹🇿 Tanzania",
		"UG" to "🇺🇬 Uganda",
		"GH" to "🇬🇭 Ghana",
		"ET" to "🇪🇹 Ethiopia",
		"SD" to "🇸🇩 Sudan",
		"DZ" to "🇩🇿 Algeria",
		"MA" to "🇲🇦 Morocco",
		"TN" to "🇹🇳 Tunisia",
		"AR" to "🇦🇷 Argentina",
		"CL" to "🇨🇱 Chile",
		"CO" to "🇨🇴 Colombia",
		"PE" to "🇵🇪 Peru",
		"VE" to "🇻🇪 Venezuela",
		"CU" to "🇨🇺 Cuba",
		"UA" to "🇺🇦 Ukraine",
		"PL" to "🇵🇱 Poland",
		"CZ" to "🇨🇿 Czech Republic",
		"HU" to "🇭🇺 Hungary",
		"RO" to "🇷🇴 Romania",
		"BG" to "🇧🇬 Bulgaria",
		"GR" to "🇬🇷 Greece",
		"SE" to "🇸🇪 Sweden",
		"NO" to "🇳🇴 Norway",
		"FI" to "🇫🇮 Finland",
		"DK" to "🇩🇰 Denmark",
		"NL" to "🇳🇱 Netherlands",
		"BE" to "🇧🇪 Belgium",
		"PT" to "🇵🇹 Portugal",
		"CH" to "🇨🇭 Switzerland",
		"AT" to "🇦🇹 Austria",
		"IE" to "🇮🇪 Ireland",
		"IS" to "🇮🇸 Iceland",
		"SK" to "🇸🇰 Slovakia",
		"SI" to "🇸🇮 Slovenia",
		"HR" to "🇭🇷 Croatia",
		"RS" to "🇷🇸 Serbia",
		"BA" to "🇧🇦 Bosnia & Herzegovina",
		"MK" to "🇲🇰 North Macedonia",
		"GE" to "🇬🇪 Georgia",
		"AM" to "🇦🇲 Armenia",
		"AZ" to "🇦🇿 Azerbaijan",
		"KZ" to "🇰🇿 Kazakhstan",
		"UZ" to "🇺🇿 Uzbekistan",
		"TM" to "🇹🇲 Turkmenistan",
		"KG" to "🇰🇬 Kyrgyzstan",
		"MM" to "🇲🇲 Myanmar",
		"KH" to "🇰🇭 Cambodia",
		"LA" to "🇱🇦 Laos",
		"MN" to "🇲🇳 Mongolia",
		"AF" to "🇦🇫 Afghanistan",
		"IQ" to "🇮🇶 Iraq",
		"SY" to "🇸🇾 Syria",
		"JO" to "🇯🇴 Jordan",
		"LB" to "🇱🇧 Lebanon",
		"QA" to "🇶🇦 Qatar",
		"OM" to "🇴🇲 Oman",
		"KW" to "🇰🇼 Kuwait",
		"BH" to "🇧🇭 Bahrain",
		"YE" to "🇾🇪 Yemen"
	)
}
