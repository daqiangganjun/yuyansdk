package com.yuyan.imemodule.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.dao.ClipboardDao
import com.yuyan.imemodule.database.dao.PhraseDao
import com.yuyan.imemodule.database.dao.SideSymbolDao
import com.yuyan.imemodule.database.dao.SkbFunDao
import com.yuyan.imemodule.database.dao.UsedSymbolDao
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.database.entry.SideSymbol
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.database.entry.UsedSymbol
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

//@Database(entities = [SideSymbol::class, Clipboard::class, UsedSymbol::class], version = 1, exportSchema = false)
@Database(entities = [SideSymbol::class, Clipboard::class, UsedSymbol::class, Phrase::class, SkbFun::class], version = 7, exportSchema = false)
abstract class DataBaseKT : RoomDatabase() {
    abstract fun sideSymbolDao(): SideSymbolDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun usedSymbolDao(): UsedSymbolDao
    abstract fun phraseDao(): PhraseDao
    abstract fun skbFunDao(): SkbFunDao
    companion object {

        // 默认数据初始化专用，与词库复制所在的单线程执行器解耦
        private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS phrase (content TEXT PRIMARY KEY NOT NULL, isKeep INTEGER NOT NULL, t9 TEXT NOT NULL, qwerty TEXT NOT NULL, lx17 TEXT NOT NULL, time INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skbfun (name TEXT KEY NOT NULL, isKeep INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (name, isKeep))")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 0, 15)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 1, 0)")
            }
        }

        // 顶栏菜单改为按 position 排序，为老库补上顺序并加入「切换键盘」
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE skbfun SET position = 0 WHERE name = 'ClipBoard' AND isKeep = 1")
                db.execSQL("UPDATE skbfun SET position = 1 WHERE name = 'Emojicon' AND isKeep = 1")
                db.execSQL("UPDATE skbfun SET position = 2 WHERE name = 'TextEdit' AND isKeep = 1")
                db.execSQL("INSERT OR IGNORE INTO skbfun (name, isKeep, position) VALUES ('SwitchKeyboard', 1, 3)")
            }
        }

        // 数字行功能已移除，清理历史库中残留的菜单记录，否则解码时会遇到未知枚举名
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM skbfun WHERE name = 'NumberRow'")
            }
        }

        // 索引名必须与 @Index 生成的名称一致，否则 Room 的 schema 校验不通过
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phrase_t9 ON phrase (t9)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phrase_qwerty ON phrase (qwerty)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phrase_lx17 ON phrase (lx17)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_time ON clipboard (time)")
            }
        }

        // allowMainThreadQueries 暂予保留：热路径查询已逐个消除，但设置类界面仍有
        // 同步调用，直接移除会在遗漏处抛异常，对常驻输入法风险过高
        val instance = Room.databaseBuilder(Launcher.instance.context, DataBaseKT::class.java, "ime_db")
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            .addCallback(object :Callback(){
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    initScope.launch { initDb() }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    initScope.launch { initPhrasesDb() }
                }
            })
            .build()

        /**
         * 触发建库与默认数据写入。
         *
         * 必须独立于词库复制所用的单线程执行器：词库约 120MB，两者共用执行器会把
         * 侧符号、常用语与候选栏菜单的默认数据初始化推迟到复制完成之后，期间唤起
         * 输入法会看到空的侧符号栏与候选栏菜单。
         */
        fun preload() {
            initScope.launch { instance.sideSymbolDao().getAllSideSymbolPinyin() }
        }
        private fun initDb() {  //初始化数据库数据
            val symbolPinyin = listOf("，", "。", "？", "！", "……", "：", "；", ".").map {  symbolKey->
                SideSymbol(symbolKey, symbolKey)
            }
            instance.sideSymbolDao().insertAll(symbolPinyin)
            val symbolNumber = listOf("%", "/", "-", "+", "*", "#", "@").map {  symbolKey->
                SideSymbol(symbolKey, symbolKey, "number")
            }
            instance.sideSymbolDao().insertAll(symbolNumber)
        }

        private fun initPhrasesDb() {  //初始化常用语数据数据
            if(instance.phraseDao().getAll().isEmpty()) {
                val phrases = listOf(
                    Phrase(content = "我的电话是__，常联系。", t9 = "9334", qwerty = "wddh", lx17 = "wddh"),
                    Phrase(content = "抱歉，我现在不方便接电话，稍后联系。", t9 = "2799", qwerty = "bqwx", lx17 = "bqwx"),
                    Phrase(content = "我正在开会，有急事请发短信。", t9 = "9995", qwerty = "wzzk", lx17 = "wwwj"),
                    Phrase(content = "我很快就到，请稍微等一会儿。", t9 = "9455", qwerty = "whkj", lx17 = "whjj"),
                    Phrase(content = "麻烦放驿站，谢谢。", t9 = "6339", qwerty = "mffy", lx17 = "mffy"),
                )
                instance.phraseDao().insertAll(phrases)
            }
            if(instance.skbFunDao().getAllMenu().isEmpty()) {
                val skbFuns = listOf(
                    // isKeep = 1 为候选栏顶部菜单，列表 reverseLayout，position 越大越靠左
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 1, position = 0),
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 1, position = 1),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 1, position = 2),
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 1, position = 3),
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 0, position = 0),
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 0, position = 1),
                    SkbFun(name = SkbMenuMode.KeyboardHeight.name, isKeep = 0, position = 2),
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 0, position = 3),
                    SkbFun(name = SkbMenuMode.Phrases.name, isKeep = 0, position = 4),
                    SkbFun(name = SkbMenuMode.DarkTheme.name, isKeep = 0, position = 5),
                    SkbFun(name = SkbMenuMode.Feedback.name, isKeep = 0, position = 6),
                    SkbFun(name = SkbMenuMode.OneHanded.name, isKeep = 0, position = 7),
                    SkbFun(name = SkbMenuMode.JianFan.name, isKeep = 0, position = 9),
                    SkbFun(name = SkbMenuMode.Mnemonic.name, isKeep = 0, position = 10),
                    SkbFun(name = SkbMenuMode.FloatKeyboard.name, isKeep = 0, position = 11),
                    SkbFun(name = SkbMenuMode.FlowerTypeface.name, isKeep = 0, position = 12),
                    SkbFun(name = SkbMenuMode.Custom.name, isKeep = 0, position = 13),
                    SkbFun(name = SkbMenuMode.Settings.name, isKeep = 0, position = 14),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 0, position = 15),
                )
                instance.skbFunDao().insertAll(skbFuns)
                // 默认菜单写入前若已有读取，缓存中会是空结果，此处使其失效
                SkbMenuCache.invalidate()
            }
        }
    }
}
