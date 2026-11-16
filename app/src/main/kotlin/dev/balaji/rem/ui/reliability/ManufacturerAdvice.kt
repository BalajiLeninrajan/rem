package dev.balaji.rem.ui.reliability

import androidx.annotation.StringRes
import dev.balaji.rem.R

/**
 * Brands whose battery managers are known to stop alarm apps, with the setting to change.
 *
 * @property slug the brand's page on dontkillmyapp.com.
 */
enum class ManufacturerAdvice(val slug: String, @StringRes val advice: Int, private vararg val brands: String) {
    HUAWEI("huawei", R.string.ui_oem_huawei, "huawei", "honor"),
    XIAOMI("xiaomi", R.string.ui_oem_xiaomi, "xiaomi", "redmi", "poco"),
    ONEPLUS("oneplus", R.string.ui_oem_oneplus, "oneplus"),
    SAMSUNG("samsung", R.string.ui_oem_samsung, "samsung"),
    OPPO("oppo", R.string.ui_oem_oppo, "oppo"),
    REALME("realme", R.string.ui_oem_oppo, "realme"),
    VIVO("vivo", R.string.ui_oem_vivo, "vivo", "iqoo"),
    ASUS("asus", R.string.ui_oem_asus, "asus"),
    ;

    val url: String get() = "$DONT_KILL_MY_APP_URL/$slug"

    companion object {
        const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com"

        /** @param manufacturer `Build.MANUFACTURER`, in whatever case the device reports. */
        fun forManufacturer(manufacturer: String): ManufacturerAdvice? {
            val brand = manufacturer.trim().lowercase()
            return entries.firstOrNull { brand in it.brands }
        }
    }
}
