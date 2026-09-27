package com.arena.bpdiary

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Модель звукового сигнала уведомлений.
 */
data class SoundOption(
    val id: String,
    val title: String,
    val desc: String,
    val rawResId: Int,
    val isPiercing: Boolean // true: для слабослышащих (пронзительный), false: лёгкий/релакс
)

/**
 * Настройки звуковых и световых уведомлений (хранятся в SharedPreferences).
 */
object AlertSettings {

    private const val PREFS = "bp_alert_prefs"
    private const val KEY_SOUND_ID = "sound_id"
    private const val KEY_SCREEN_FLASH = "screen_flash"
    private const val KEY_TORCH_FLASH = "torch_flash"

    val PIERCING_SOUNDS = listOf(
        SoundOption("pierce_1", "1. Пульс-тревога (750 Гц)", "Трёхкратный акцентированный гудок", R.raw.sound_pierce_1_beeps, true),
        SoundOption("pierce_2", "2. Медицинская сирена (600/900 Гц)", "Двухтональный контрастный звук", R.raw.sound_pierce_2_siren, true),
        SoundOption("pierce_3", "3. Быстрый импульс (800 Гц)", "Пять коротких тактов, пробивающих шум", R.raw.sound_pierce_3_rapid, true),
        SoundOption("pierce_4", "4. Низкий клаксон (520 Гц)", "Мощный низкий тон с богатыми гармониками", R.raw.sound_pierce_4_horn, true),
        SoundOption("pierce_5", "5. Восходящая сирена (500–1000 Гц)", "Свип частоты по зоне сохранного слуха", R.raw.sound_pierce_5_sweep, true),
        SoundOption("pierce_6", "6. Прерывистый зуммер (700 Гц)", "Быстрая трель, привлекающая внимание", R.raw.sound_pierce_6_buzzer, true),
        SoundOption("pierce_7", "7. Металлический гонг (650 Гц)", "Резкий звонкий удар колокола", R.raw.sound_pierce_7_gong, true),
        SoundOption("pierce_8", "8. Тревожное трехзвучие (550-950 Гц)", "Три нарастающих пронзительных тона", R.raw.sound_pierce_8_tri, true),
        SoundOption("pierce_9", "9. Ритмичный сигнал SOS (850 Гц)", "Чёткий телеграфный ритм", R.raw.sound_pierce_9_sos, true),
        SoundOption("pierce_10", "10. Механический будильник (800 Гц)", "Классическая громкая трель", R.raw.sound_pierce_10_alarm, true)
    )

    val CALM_SOUNDS = listOf(
        SoundOption("calm_1", "1. Рассвет в саду (маримба и струнные)", "Маримба с аккордами струнного оркестра и флейтой", R.raw.sound_calm_1_marimba, false),
        SoundOption("calm_2", "2. Серебряные колокольчики", "Перезвон челесты, глокеншпиля и мягких скрипок", R.raw.sound_calm_2_chime, false),
        SoundOption("calm_3", "3. Кельтская арфа и флейта", "Воздушное арпеджио арфы со струнным ансамблем", R.raw.sound_calm_3_harp, false),
        SoundOption("calm_4", "4. Капли росы и Rhodes", "Кристальные капли с тёплым электропиано и маримбой", R.raw.sound_calm_4_drop, false),
        SoundOption("calm_5", "5. Тёплый колокол (528 Гц)", "Медитативный колокол 528 Гц со струнной подложкой", R.raw.sound_calm_5_warmbell, false),
        SoundOption("calm_6", "6. Хрустальная челеста и рояль", "Фортепианная гармония со звонкими переливами челесты", R.raw.sound_calm_6_celesta, false),
        SoundOption("calm_7", "7. Концертный рояль", "Богатое мажорное фортепиано со струнным оркестром", R.raw.sound_calm_7_piano, false),
        SoundOption("calm_8", "8. Утренний рассвет (гитара и флейта)", "Акустический перебор гитары, Rhodes и нежная флейта", R.raw.sound_calm_8_sunrise, false),
        SoundOption("calm_9", "9. Лесная флейта и струнный квартет", "Мелодия флейты в окружении арфы и струнных", R.raw.sound_calm_9_flute, false),
        SoundOption("calm_10", "10. Гармония Дзен (432 Гц)", "Тибетская поющая чаша 432 Гц, челеста и колокольчики", R.raw.sound_calm_10_zen, false)
    )

    val ALL_SOUNDS = PIERCING_SOUNDS + CALM_SOUNDS

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSelectedSoundId(ctx: Context): String =
        sp(ctx).getString(KEY_SOUND_ID, "pierce_1") ?: "pierce_1"

    fun setSelectedSoundId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_SOUND_ID, id).apply()
    }

    fun getSelectedSound(ctx: Context): SoundOption {
        val id = getSelectedSoundId(ctx)
        return ALL_SOUNDS.firstOrNull { it.id == id } ?: PIERCING_SOUNDS.first()
    }

    fun getSoundUri(ctx: Context): Uri {
        val sound = getSelectedSound(ctx)
        return Uri.parse("android.resource://${ctx.packageName}/${sound.rawResId}")
    }

    fun isScreenFlashEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_SCREEN_FLASH, true) // по умолчанию включено (мигание экрана)

    fun setScreenFlashEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SCREEN_FLASH, enabled).apply()
    }

    fun isTorchFlashEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_TORCH_FLASH, true) // по умолчанию включено (фонарик для слабослышащих)

    fun setTorchFlashEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TORCH_FLASH, enabled).apply()
    }
}
