package com.beerespect.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.beerespect.game.audio.Sfx
import com.beerespect.game.audio.SoundEngine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val HIVE_RADIUS = 92f

class BeeGameView(context: Context) : View(context) {

    private enum class Mode { MENU, PLAYING, SHOP, GAMEOVER }

    private class Progress(c: Context) {
        private val prefs = c.applicationContext.getSharedPreferences("bee_respect_save", Context.MODE_PRIVATE)

        var honey: Int
            get() = prefs.getInt("honey", 40)
            set(value) = prefs.edit().putInt("honey", value.coerceAtLeast(0)).apply()

        var lifetime: Int
            get() = prefs.getInt("lifetime_honey", 0)
            set(value) = prefs.edit().putInt("lifetime_honey", value.coerceAtLeast(0)).apply()

        var bestWave: Int
            get() = prefs.getInt("best_wave", 0)
            set(value) = prefs.edit().putInt("best_wave", value.coerceAtLeast(0)).apply()

        var skin: Int
            get() = prefs.getInt("skin", 0).coerceIn(0, 3)
            set(value) = prefs.edit().putInt("skin", value.coerceIn(0, 3)).apply()

        var sound: Boolean
            get() = prefs.getBoolean("sound", true)
            set(value) = prefs.edit().putBoolean("sound", value).apply()

        var swarm: Int
            get() = prefs.getInt("up_swarm", 0).coerceIn(0, 14)
            set(value) = prefs.edit().putInt("up_swarm", value.coerceIn(0, 14)).apply()

        var sting: Int
            get() = prefs.getInt("up_sting", 0).coerceIn(0, 8)
            set(value) = prefs.edit().putInt("up_sting", value.coerceIn(0, 8)).apply()

        var speed: Int
            get() = prefs.getInt("up_speed", 0).coerceIn(0, 8)
            set(value) = prefs.edit().putInt("up_speed", value.coerceIn(0, 8)).apply()

        var armor: Int
            get() = prefs.getInt("up_armor", 0).coerceIn(0, 8)
            set(value) = prefs.edit().putInt("up_armor", value.coerceIn(0, 8)).apply()

        fun level(kind: Int): Int = when (kind) {
            0 -> swarm
            1 -> sting
            2 -> speed
            3 -> armor
            else -> 0
        }

        fun maxLevel(kind: Int): Int = if (kind == 0) 14 else 8

        fun cost(kind: Int): Int {
            val level = level(kind)
            val base = when (kind) {
                0 -> 24
                1 -> 30
                2 -> 28
                3 -> 34
                else -> 9999
            }
            return base + level * level * 6 + level * 14
        }

        fun unlocked(index: Int): Boolean = when (index) {
            0 -> true
            1 -> lifetime >= 120
            2 -> lifetime >= 400
            3 -> lifetime >= 900
            else -> false
        }

        fun need(index: Int): Int = when (index) {
            1 -> 120
            2 -> 400
            3 -> 900
            else -> 0
        }

        fun buy(kind: Int): Boolean {
            val current = level(kind)
            if (current >= maxLevel(kind)) return false
            val price = cost(kind)
            if (honey < price) return false
            honey -= price
            when (kind) {
                0 -> swarm = current + 1
                1 -> sting = current + 1
                2 -> speed = current + 1
                3 -> armor = current + 1
            }
            return true
        }

        fun addHoney(value: Int) {
            if (value <= 0) return
            honey += value
            lifetime += value
        }
    }

    private open class Actor {
        var x = 0f
        var y = 0f
        var z = 0f
        var yaw = 0f
        var phase = 0f
        var flash = 0f
        var hp = 0f
        var maxHp = 0f
        var radius = 20f
        var kind = 0
        var ally = false
        var alive = true
        var cd = 0f
        var speed = 0f
        var damage = 0f
    }

    private class PlayerBee : Actor() {
        var dash = 0f
        var dashCd = 0f
        var invuln = 0f
    }

    private class Bullet {
        var x = 0f
        var y = 0f
        var z = 0f
        var vx = 0f
        var vy = 0f
        var vz = 0f
        var life = 0f
        var friendly = true
        var damage = 0f
        var kind = 0
    }

    private class Spark {
        var active = false
        var x = 0f
        var y = 0f
        var z = 0f
        var vx = 0f
        var vy = 0f
        var vz = 0f
        var life = 0f
        var maxLife = 1f
        var size = 1f
        var color = Color.WHITE
    }

    private class Pollen {
        var x = 0f
        var y = 0f
        var z = 0f
        var vx = 0f
        var vy = 0f
        var vz = 0f
        var life = 0f
        var phase = 0f
    }

    private class Note {
        var x = 0f
        var y = 0f
        var z = 0f
        var text = ""
        var color = Color.WHITE
        var life = 0f
    }

    private class FlowerState(val x: Float, val z: Float, var phase: Float, var bloom: Float)

    private class Skin(val name: String, val body: Int, val dark: Int, val light: Int, val wing: Int, val need: Int)

    private val progress = Progress(context)
    private val sound = SoundEngine()

    private val skins = listOf(
        Skin("Классическая", Color.parseColor("#FFC928"), Color.parseColor("#6C3B00"), Color.parseColor("#FFF2B0"), Color.parseColor("#D8F6FF"), 0),
        Skin("Ледяная", Color.parseColor("#7CE7FF"), Color.parseColor("#174F78"), Color.parseColor("#E6FBFF"), Color.parseColor("#B6F0FF"), 120),
        Skin("Мистическая", Color.parseColor("#B06CFF"), Color.parseColor("#3A1A5E"), Color.parseColor("#F2DEFF"), Color.parseColor("#E2C2FF"), 400),
        Skin("Огненная", Color.parseColor("#FF8A2A"), Color.parseColor("#6A1B00"), Color.parseColor("#FFE0A8"), Color.parseColor("#FFD08A"), 900)
    )
    private val upgradeNames = arrayOf("РАЗМЕР РОЯ", "СИЛА ЖАЛА", "СКОРОСТЬ", "БРОНЯ УЛЬЯ")

    private val skyPaint = Paint()
    private val meadowPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E50000")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val tmp = RectF()
    private val path = Path()

    private val player = PlayerBee()
    private val hiveMarker = Actor().apply { kind = 9; radius = HIVE_RADIUS }
    private val allies = ArrayList<Actor>()
    private val enemies = ArrayList<Actor>()
    private val bullets = ArrayList<Bullet>()
    private val pollens = ArrayList<Pollen>()
    private val notes = ArrayList<Note>()
    private val flowers = ArrayList<FlowerState>()
    private val drawQueue = ArrayList<Actor>()
    private val sparks = Array(260) { Spark() }
    private var sparkCursor = 0

    private val skinRects = Array(4) { RectF() }
    private val upgradeRects = Array(4) { RectF() }
    private val shopRects = Array(4) { RectF() }
    private val playRect = RectF()
    private val soundRect = RectF()
    private val pauseRect = RectF()
    private val stingRect = RectF()
    private val dashRect = RectF()
    private val ultRect = RectF()
    private val nextRect = RectF()
    private val shopMenuRect = RectF()
    private val menuRect = RectF()
    private val restartRect = RectF()
    private val resumeRect = RectF()
    private val quitRect = RectF()

    private val pointers = HashMap<Int, Int>()
    private val pressed = BooleanArray(3)
    private var joyId = -1
    private var joyX = 0f
    private var joyY = 0f
    private var moveX = 0f
    private var moveY = 0f

    private var vw = 0f
    private var vh = 0f
    private var s = 1f
    private var zoom = 1f
    private var horizon = 0f
    private var camX = 0f
    private var camZ = 0f
    private var px = 0f
    private var py = 0f
    private var gy = 0f
    private var time = 0f
    private var lastFrameNs = 0L

    private var mode = Mode.MENU
    private var paused = false
    private var wave = 0
    private var score = 0
    private var spawnLeft = 0
    private var spawnTimer = 0f
    private var waveActive = false
    private var clearTimer = 0f
    private var hiveHp = 0f
    private var hiveMax = 0f
    private var ult = 0f
    private var ultTime = 0f
    private var ultTick = 0f
    private var shake = 0f
    private var message = ""
    private var messageTime = 0f
    private var pollenTimer = 4f
    private var soundStarted = false

    private var soundOn = true

    init {
        setBackgroundColor(Color.parseColor("#88CEF0"))
        isClickable = true
        soundOn = progress.sound
        sound.setEnabled(soundOn)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vw = w.toFloat()
        vh = h.toFloat()
        if (vw <= 0f || vh <= 0f) return
        s = min(vw / 960f, vh / 540f).coerceIn(0.5f, 2.4f)
        zoom = s
        horizon = vh * 0.40f
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizon + 2f,
            intArrayOf(Color.parseColor("#4FBDF5"), Color.parseColor("#9EE3FF"), Color.parseColor("#FFF0B6")),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        meadowPaint.shader = LinearGradient(
            0f, horizon, 0f, vh,
            intArrayOf(Color.parseColor("#A9EA6B"), Color.parseColor("#6FC647"), Color.parseColor("#3F8B32")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        layoutUi()
        prepareFlowers()
    }

    private fun layoutUi() {
        val gap = 12f * s
        val contentWidth = min(vw * 0.92f, 1040f * s)
        val left = (vw - contentWidth) / 2f
        val cardW = (contentWidth - gap * 3f) / 4f
        val cardH = 108f * s
        val cardY = vh * 0.27f
        for (i in 0..3) {
            val x = left + i * (cardW + gap)
            skinRects[i].set(x, cardY, x + cardW, cardY + cardH)
        }
        val upgradeW = cardW
        val upgradeH = 100f * s
        val upgradeY = vh * 0.53f
        for (i in 0..3) {
            val x = left + i * (upgradeW + gap)
            upgradeRects[i].set(x, upgradeY, x + upgradeW, upgradeY + upgradeH)
        }
        val buttonW = 320f * s
        val buttonH = 68f * s
        playRect.set(vw / 2f - buttonW / 2f, vh * 0.81f, vw / 2f + buttonW / 2f, vh * 0.81f + buttonH)
        soundRect.set(vw - 96f * s, 14f * s, vw - 14f * s, 62f * s)
        pauseRect.set(vw - 60f * s, 14f * s, vw - 14f * s, 68f * s)
        stingRect.set(vw - 200f * s, vh - 112f * s, vw - 116f * s, vh - 28f * s)
        dashRect.set(vw - 214f * s, vh - 230f * s, vw - 130f * s, vh - 146f * s)
        ultRect.set(vw - 132f * s, vh - 128f * s, vw - 20f * s, vh - 16f * s)

        val shopW = min(vw * 0.27f, 240f * s)
        val shopH = min(vh * 0.18f, 110f * s)
        val shopLeft = (vw - (shopW * 2f + gap)) / 2f
        val shopY = vh * 0.38f
        for (i in 0..3) {
            val x = shopLeft + (i % 2) * (shopW + gap)
            val y = shopY + (i / 2) * (shopH + gap)
            shopRects[i].set(x, y, x + shopW, y + shopH)
        }
        nextRect.set(vw / 2f - 190f * s, vh * 0.82f, vw / 2f + 190f * s, vh * 0.82f + 68f * s)
        shopMenuRect.set(vw / 2f + 242f * s, vh * 0.15f, vw / 2f + 370f * s, vh * 0.15f + 44f * s)
        menuRect.set(vw / 2f - 300f * s, vh * 0.88f, vw / 2f - 12f * s, vh * 0.88f + 54f * s)
        restartRect.set(vw / 2f + 12f * s, vh * 0.88f, vw / 2f + 300f * s, vh * 0.88f + 54f * s)
        resumeRect.set(vw / 2f - 200f * s, vh * 0.58f, vw / 2f + 200f * s, vh * 0.58f + 68f * s)
        quitRect.set(vw / 2f - 200f * s, vh * 0.70f, vw / 2f + 200f * s, vh * 0.70f + 60f * s)
    }

    private fun prepareFlowers() {
        if (flowers.isNotEmpty()) return
        val random = Random(77)
        for (i in 0 until 24) {
            val angle = i * (6.283185f / 24f)
            val radius = 190f + random.nextFloat() * 700f
            flowers.add(FlowerState(cos(angle) * radius, sin(angle) * radius, random.nextFloat() * 6.28f, random.nextFloat() * 0.7f))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        sound.stop()
        soundStarted = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        try {
            drawBackdrop(canvas)
            tick()
            drawField(canvas)
            if (mode == Mode.MENU) {
                drawMenu(canvas)
            } else {
                drawHud(canvas)
                when (mode) {
                    Mode.SHOP -> drawShop(canvas)
                    Mode.GAMEOVER -> drawGameOver(canvas)
                    else -> if (paused) drawPause(canvas)
                }
            }
        } catch (e: Throwable) {
            drawDrawError(canvas, e)
        }
        postInvalidateOnAnimation()
    }

    private fun drawDrawError(canvas: Canvas, e: Throwable) {
        try {
            val message = "ОШИБКА: " + (e.message ?: e.javaClass.simpleName)
            val size = (20f * s).coerceIn(16f, 30f)
            errorPaint.textSize = size
            textBold.textSize = size
            textBold.color = Color.parseColor("#7A1500")
            textBold.textAlign = Paint.Align.CENTER
            canvas.drawText(message, vw / 2f + 2f, vh / 2f + 2f, textBold)
            canvas.drawText(message, vw / 2f, vh / 2f, errorPaint)
        } catch (_: Throwable) {
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        if (lastFrameNs == 0L) lastFrameNs = now
        val dt = ((now - lastFrameNs).coerceAtMost(50_000_000L)) / 1_000_000_000f
        lastFrameNs = now
        update(dt)
    }

    private fun update(dt: Float) {
        time += dt
        if (messageTime > 0f) messageTime -= dt
        shake = max(0f, shake - dt * 2.4f)
        updateFlowers(dt)
        updateSparks(dt)
        updateNotes(dt)

        if (mode == Mode.MENU) {
            camX = sin(time * 0.15f) * 80f
            camZ = cos(time * 0.15f) * 60f
            player.x = sin(time * 0.42f) * 100f
            player.z = 210f + cos(time * 0.42f) * 60f
            player.y = 36f + sin(time * 3f) * 5f
            player.yaw = sin(time * 0.6f) * 0.9f
            player.phase += dt * 24f
            updateAllies(dt, true)
            return
        }

        camX = (player.x * 0.55f).coerceIn(-280f, 280f)
        camZ = (player.z * 0.42f).coerceIn(-220f, 220f)
        if (mode != Mode.PLAYING || paused) return

        updatePlayer(dt)
        updateAllies(dt, false)
        updateSpawning(dt)
        updateEnemies(dt)
        updateBullets(dt)
        updatePollens(dt)
        updateUlt(dt)
        updateWave(dt)
        hiveHp = min(hiveMax, hiveHp + (1.4f + progress.armor * 0.7f) * dt)
    }

    private fun updateFlowers(dt: Float) {
        for (flower in flowers) {
            flower.phase += dt * 1.3f
            if (flower.bloom > 0f) flower.bloom = max(0f, flower.bloom - dt * 0.12f)
        }
    }

    private fun updateSparks(dt: Float) {
        for (spark in sparks) {
            if (!spark.active) continue
            spark.life -= dt
            if (spark.life <= 0f) {
                spark.active = false
                continue
            }
            spark.vy -= 190f * dt
            spark.x += spark.vx * dt
            spark.y += spark.vy * dt
            spark.z += spark.vz * dt
            if (spark.y < 1f) {
                spark.y = 1f
                spark.vy = abs(spark.vy) * 0.3f
            }
        }
    }

    private fun updateNotes(dt: Float) {
        val iterator = notes.iterator()
        while (iterator.hasNext()) {
            val note = iterator.next()
            note.life -= dt
            note.y += 42f * dt
            if (note.life <= 0f) iterator.remove()
        }
    }

    private fun updatePlayer(dt: Float) {
        val length = hypot(moveX, moveY).coerceIn(0f, 1f)
        val speed = (340f + progress.speed * 34f) * (if (player.dash > 0f) 2.4f else 1f)
        if (length > 0.08f) {
            player.x += moveX / length * speed * dt
            player.z -= moveY / length * speed * dt
            player.yaw = atan2(moveX, moveY)
        } else {
            val target = nearestEnemy(player, 460f)
            if (target != null) player.yaw = atan2(target.x - player.x, -(target.z - player.z))
        }
        val radius = hypot(player.x, player.z)
        if (radius > 850f) {
            player.x *= 850f / radius
            player.z *= 850f / radius
        }
        player.y = 36f + sin(time * 3.4f) * 5f + (if (player.dash > 0f) 12f else 0f)
        player.phase += dt * (30f + progress.speed * 2f)
        if (player.cd > 0f) player.cd -= dt
        if (player.dash > 0f) player.dash -= dt
        if (player.dashCd > 0f) player.dashCd -= dt
        if (player.invuln > 0f) player.invuln -= dt
        if (player.flash > 0f) player.flash -= dt
        sound.setEngineActive(length > 0.1f)
        if (pressed[0] && player.cd <= 0f) fireSting()
    }

    private fun fireSting() {
        player.cd = 0.34f / (1f + progress.sting * 0.16f)
        val target = nearestEnemy(player, 470f)
        val yaw = if (target != null) atan2(target.x - player.x, -(target.z - player.z)) else player.yaw
        val shots = 1 + (if (progress.sting >= 3) 1 else 0) + (if (progress.sting >= 6) 1 else 0)
        for (i in 0 until shots) {
            val angle = yaw + (i - (shots - 1) / 2f) * 0.13f
            addBullet(
                player.x + sin(angle) * 26f, player.y, player.z - cos(angle) * 26f,
                sin(angle) * 780f, 0f, -cos(angle) * 780f,
                1.4f, true, 14f + progress.sting * 4f, 0
            )
        }
        sound.play(Sfx.STING)
        burst(player.x, player.y, player.z, 4, 120f, 3f, Color.parseColor("#FFF4A0"))
    }

    private fun updateAllies(dt: Float, idle: Boolean) {
        val wanted = min(15, 1 + progress.swarm)
        while (allies.size < wanted) {
            val ally = Actor()
            ally.ally = true
            ally.x = player.x
            ally.y = player.y
            ally.z = player.z + 46f
            allies.add(ally)
        }
        while (allies.size > wanted) allies.removeAt(allies.size - 1)
        for (i in allies.indices) {
            val ally = allies[i]
            ally.phase += dt * 5f
            val focus = if (idle) null else nearestEnemy(ally, 340f)
            if (focus != null) {
                val angle = time * 2.2f + i * 1.7f
                val targetX = focus.x + cos(angle) * 48f
                val targetZ = focus.z + sin(angle) * 48f
                val targetY = focus.y + 20f
                val k = min(1f, dt * 4.4f)
                ally.x += (targetX - ally.x) * k
                ally.y += (targetY - ally.y) * k
                ally.z += (targetZ - ally.z) * k
                ally.yaw = atan2(focus.x - ally.x, -(focus.z - ally.z))
                ally.cd -= dt
                if (ally.cd <= 0f) {
                    ally.cd = max(0.34f, 0.92f - progress.swarm * 0.038f)
                    fireAllyBullet(ally, focus)
                }
            } else {
                val side = i - (allies.size - 1) / 2f
                val back = 56f + abs(side) * 14f
                val targetX = player.x - sin(player.yaw) * back + cos(player.yaw) * side * 16f
                val targetZ = player.z + cos(player.yaw) * back + sin(player.yaw) * side * 16f
                val targetY = player.y + 10f + sin(time * 3f + i) * 8f
                val k = min(1f, dt * 4f)
                ally.x += (targetX - ally.x) * k
                ally.y += (targetY - ally.y) * k
                ally.z += (targetZ - ally.z) * k
                ally.yaw = player.yaw
            }
        }
    }

    private fun fireAllyBullet(ally: Actor, focus: Actor) {
        val dx = focus.x - ally.x
        val dy = focus.y - ally.y
        val dz = focus.z - ally.z
        val length = max(1f, hypot(hypot(dx, dy), dz))
        val power = 660f
        addBullet(ally.x, ally.y, ally.z, dx / length * power, dy / length * power, dz / length * power, 1.3f, true, 6f + progress.swarm * 0.5f, 1)
    }

    private fun updateSpawning(dt: Float) {
        if (!waveActive || spawnLeft <= 0) return
        spawnTimer -= dt
        if (spawnTimer > 0f) return
        spawnTimer = max(0.34f, 1.2f - wave * 0.035f)
        val roll = Random.nextFloat()
        val kind = when {
            wave < 3 -> 0
            wave < 5 -> if (roll < 0.7f) 0 else 1
            else -> if (roll < 0.44f) 0 else if (roll < 0.72f) 1 else 2
        }
        spawnEnemy(kind)
        spawnLeft--
    }

    private fun spawnEnemy(kind: Int) {
        val angle = Random.nextFloat() * 6.283185f
        val distance = 620f + Random.nextFloat() * 190f
        val enemy = Actor()
        enemy.kind = kind
        enemy.x = cos(angle) * distance
        enemy.z = sin(angle) * distance
        enemy.y = 26f + Random.nextFloat() * 22f
        enemy.phase = Random.nextFloat() * 6.28f
        when (kind) {
            0 -> {
                enemy.hp = 34f + wave * 7f
                enemy.speed = 82f + wave * 2f
                enemy.damage = 9f + wave * 0.5f
                enemy.radius = 22f
            }
            1 -> {
                enemy.hp = 28f + wave * 6f
                enemy.speed = 68f + wave * 1.6f
                enemy.damage = 7f + wave * 0.4f
                enemy.radius = 20f
            }
            2 -> {
                enemy.hp = 25f + wave * 5f
                enemy.speed = 112f + wave * 2.4f
                enemy.damage = 10f + wave * 0.45f
                enemy.radius = 18f
            }
            else -> {
                enemy.hp = 420f + wave * 55f
                enemy.speed = 54f
                enemy.damage = 30f + wave * 1.1f
                enemy.radius = 52f
                enemy.y = 66f
            }
        }
        enemy.maxHp = enemy.hp
        enemies.add(enemy)
    }

    private fun updateEnemies(dt: Float) {
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.phase += dt * 6f
            if (enemy.flash > 0f) enemy.flash -= dt
            val hiveDistance = hypot(enemy.x, enemy.z)
            val playerDistance = hypot(player.x - enemy.x, player.z - enemy.z)
            var targetX = 0f
            var targetZ = 0f
            var targetY = 22f
            if (enemy.kind == 2 && playerDistance < 320f) {
                targetX = player.x
                targetZ = player.z
                targetY = player.y
            }
            val dx = targetX - enemy.x
            val dz = targetZ - enemy.z
            val distance = max(1f, hypot(dx, dz))
            val stopDistance = HIVE_RADIUS + enemy.radius + 10f
            if (hiveDistance > stopDistance) {
                enemy.x += dx / distance * enemy.speed * dt
                enemy.z += dz / distance * enemy.speed * dt
            }
            enemy.y += (targetY - enemy.y) * min(1f, dt * 1.3f)
            enemy.yaw = atan2(dx, -dz)
            enemy.cd -= dt
            if (hiveDistance < stopDistance + 14f && enemy.cd <= 0f) {
                enemy.cd = 1.15f
                damageHive(enemy.damage)
                burst(enemy.x, enemy.y, enemy.z, 12, 180f, 5f, Color.parseColor("#FFD05A"))
            }
            if (enemy.kind == 1 && hiveDistance < 460f && enemy.cd <= 0f) {
                enemy.cd = 2.1f
                shootAt(enemy, player.x, player.y, player.z, 300f, enemy.damage)
            }
            if (enemy.kind == 3 && enemy.cd <= 0f) {
                enemy.cd = 2.2f
                for (i in -1..1) shootAt(enemy, player.x + i * 44f, player.y, player.z, 340f, enemy.damage * 0.42f)
                shake = max(shake, 1.2f)
                burst(enemy.x, enemy.y, enemy.z, 24, 240f, 6f, Color.parseColor("#FF6A2A"))
            }
            if (playerDistance < enemy.radius + 28f && player.invuln <= 0f && player.dash <= 0f) {
                hurtPlayer(enemy.damage * 0.6f)
            }
            if (!enemy.alive) iterator.remove()
        }
    }

    private fun shootAt(enemy: Actor, x: Float, y: Float, z: Float, power: Float, damage: Float) {
        val dx = x - enemy.x
        val dy = y - enemy.y
        val dz = z - enemy.z
        val length = max(1f, hypot(hypot(dx, dy), dz))
        addBullet(enemy.x, enemy.y, enemy.z, dx / length * power, dy / length * power, dz / length * power, 3f, false, damage, 2)
    }

    private fun updateBullets(dt: Float) {
        val iterator = bullets.iterator()
        while (iterator.hasNext()) {
            val bullet = iterator.next()
            bullet.x += bullet.vx * dt
            bullet.y += bullet.vy * dt
            bullet.z += bullet.vz * dt
            bullet.life -= dt
            var remove = bullet.life <= 0f || bullet.y < 0f
            if (!remove && bullet.friendly) {
                for (enemy in enemies) {
                    if (enemy.alive && hypot(bullet.x - enemy.x, bullet.z - enemy.z) < enemy.radius + 12f && abs(bullet.y - enemy.y) < 56f) {
                        damageEnemy(enemy, bullet.damage)
                        burst(bullet.x, bullet.y, bullet.z, 5, 110f, 3f, Color.parseColor("#FFF6B0"))
                        remove = true
                        break
                    }
                }
            } else if (!remove) {
                if (hypot(bullet.x - player.x, bullet.z - player.z) < 26f && abs(bullet.y - player.y) < 50f) {
                    hurtPlayer(bullet.damage)
                    remove = true
                } else if (hypot(bullet.x, bullet.z) < HIVE_RADIUS + 8f && bullet.y < 90f) {
                    damageHive(bullet.damage * 0.8f)
                    remove = true
                }
            }
            if (remove) iterator.remove()
        }
    }

    private fun updatePollens(dt: Float) {
        pollenTimer -= dt
        if (pollenTimer <= 0f) {
            pollenTimer = 7f
            val flower = flowers[Random.nextInt(flowers.size)]
            flower.bloom = 1.4f
            repeat(4) {
                val angle = Random.nextFloat() * 6.283185f
                val pollen = Pollen()
                pollen.x = flower.x
                pollen.y = 42f
                pollen.z = flower.z
                pollen.vx = cos(angle) * 42f
                pollen.vy = 45f + Random.nextFloat() * 35f
                pollen.vz = sin(angle) * 42f
                pollen.life = 12f
                pollen.phase = Random.nextFloat() * 6.28f
                pollens.add(pollen)
            }
        }
        val iterator = pollens.iterator()
        while (iterator.hasNext()) {
            val pollen = iterator.next()
            pollen.life -= dt
            pollen.phase += dt * 3f
            pollen.x += pollen.vx * dt
            pollen.z += pollen.vz * dt
            pollen.y += (pollen.vy + sin(pollen.phase) * 22f) * dt
            if (hypot(pollen.x - player.x, pollen.z - player.z) < 38f && abs(pollen.y - player.y) < 58f) {
                progress.addHoney(3)
                score += 30
                ult = min(100f, ult + 8f)
                sound.play(Sfx.POLLEN)
                addNote(pollen.x, pollen.y, pollen.z, "+3 МЁДА", Color.parseColor("#FFE45C"))
                burst(pollen.x, pollen.y, pollen.z, 10, 130f, 4f, Color.parseColor("#FFF0A0"))
                iterator.remove()
            } else if (pollen.life <= 0f || pollen.y > 190f) {
                iterator.remove()
            }
        }
    }

    private fun updateUlt(dt: Float) {
        if (ultTime <= 0f) return
        ultTime -= dt
        ultTick -= dt
        if (ultTick > 0f) return
        ultTick = 0.18f
        for (enemy in enemies) {
            if (enemy.alive) damageEnemy(enemy, 22f + wave * 1.2f)
        }
        hiveHp = min(hiveMax, hiveHp + 3f)
        burst(player.x, player.y, player.z, 8, 220f, 6f, Color.parseColor("#FFE66A"))
    }

    private fun updateWave(dt: Float) {
        if (!waveActive || spawnLeft > 0 || enemies.isNotEmpty()) return
        clearTimer += dt
        if (clearTimer < 1.2f) return
        waveActive = false
        sound.setEngineActive(false)
        val bonus = 14 + wave * 4
        progress.addHoney(bonus)
        score += 120 + wave * 30
        if (wave > progress.bestWave) progress.bestWave = wave
        mode = Mode.SHOP
        message = ""
        sound.play(Sfx.WAVE)
    }

    private fun damageEnemy(enemy: Actor, amount: Float) {
        if (!enemy.alive) return
        enemy.hp -= amount
        enemy.flash = 0.12f
        if (enemy.hp > 0f) return
        enemy.alive = false
        val reward = if (enemy.kind == 3) 60 + wave * 2 else 3 + wave / 5
        progress.addHoney(reward)
        score += (if (enemy.kind == 3) 900 else 45 + wave * 8)
        ult = min(100f, ult + (if (enemy.kind == 3) 35f else 5f))
        addNote(enemy.x, enemy.y + 30f, enemy.z, "+$reward", Color.parseColor("#FFE45C"))
        burst(enemy.x, enemy.y, enemy.z, if (enemy.kind == 3) 70 else 20, 240f, 6f, Color.parseColor("#FF9F3C"))
        sound.play(Sfx.EXPLODE)
        shake = max(shake, if (enemy.kind == 3) 2f else 0.5f)
    }

    private fun damageHive(amount: Float) {
        hiveHp -= amount
        shake = max(shake, 0.8f)
        burst(0f, 40f, 0f, 10, 150f, 5f, Color.parseColor("#FFD24A"))
        if (hiveHp <= 0f) {
            hiveHp = 0f
            gameOver()
        }
    }

    private fun hurtPlayer(amount: Float) {
        if (player.invuln > 0f || player.dash > 0f) return
        player.hp -= amount
        player.invuln = 0.6f
        player.flash = 0.2f
        sound.play(Sfx.HIT)
        shake = max(shake, 0.7f)
        burst(player.x, player.y, player.z, 10, 150f, 4f, Color.parseColor("#FF6A5A"))
        if (player.hp <= 0f) {
            player.hp = 0f
            gameOver()
        }
    }

    private fun gameOver() {
        if (mode == Mode.GAMEOVER) return
        if (wave > progress.bestWave) progress.bestWave = wave
        mode = Mode.GAMEOVER
        message = ""
        sound.setEngineActive(false)
        sound.play(Sfx.EXPLODE)
        shake = 2f
    }

    private fun toMenu() {
        mode = Mode.MENU
        paused = false
        enemies.clear()
        bullets.clear()
        pollens.clear()
        notes.clear()
        allies.clear()
        for (spark in sparks) spark.active = false
        pointers.clear()
        joyId = -1
        moveX = 0f
        moveY = 0f
        pressed[0] = false
        sound.setEngineActive(false)
        sound.play(Sfx.CLICK)
        sound.resume()
    }

    private fun nearestEnemy(from: Actor, range: Float): Actor? {
        var best: Actor? = null
        var bestDistance = range
        for (enemy in enemies) {
            if (!enemy.alive) continue
            val distance = hypot(from.x - enemy.x, from.z - enemy.z)
            if (distance < bestDistance) {
                bestDistance = distance
                best = enemy
            }
        }
        return best
    }

    private fun addBullet(
        x: Float, y: Float, z: Float,
        vx: Float, vy: Float, vz: Float,
        life: Float, friendly: Boolean, damage: Float, kind: Int
    ) {
        val bullet = Bullet()
        bullet.x = x
        bullet.y = y
        bullet.z = z
        bullet.vx = vx
        bullet.vy = vy
        bullet.vz = vz
        bullet.life = life
        bullet.friendly = friendly
        bullet.damage = damage
        bullet.kind = kind
        bullets.add(bullet)
    }

    private fun burst(x: Float, y: Float, z: Float, count: Int, power: Float, size: Float, color: Int) {
        repeat(count) {
            val spark = sparks[sparkCursor]
            sparkCursor = (sparkCursor + 1) % sparks.size
            val angle = Random.nextFloat() * 6.283185f
            val speed = power * (0.35f + Random.nextFloat() * 0.65f)
            spark.active = true
            spark.x = x
            spark.y = y
            spark.z = z
            spark.vx = cos(angle) * speed
            spark.vy = (Random.nextFloat() - 0.3f) * speed
            spark.vz = sin(angle) * speed
            spark.maxLife = 0.35f + Random.nextFloat() * 0.55f
            spark.life = spark.maxLife
            spark.size = size * (0.6f + Random.nextFloat() * 0.8f)
            spark.color = color
        }
    }

    private fun addNote(x: Float, y: Float, z: Float, value: String, color: Int) {
        if (notes.size > 24) notes.removeAt(0)
        val note = Note()
        note.x = x
        note.y = y
        note.z = z
        note.text = value
        note.color = color
        note.life = 1.1f
        notes.add(note)
    }

    private fun startGame() {
        wave = 0
        score = 0
        ult = 0f
        ultTime = 0f
        ultTick = 0f
        hiveMax = 320f + progress.armor * 90f
        hiveHp = hiveMax
        player.x = 0f
        player.z = 235f
        player.y = 36f
        player.yaw = 0f
        player.maxHp = 100f + progress.armor * 10f
        player.hp = player.maxHp
        player.cd = 0f
        player.dash = 0f
        player.dashCd = 0f
        player.invuln = 0f
        player.flash = 0f
        enemies.clear()
        bullets.clear()
        pollens.clear()
        notes.clear()
        allies.clear()
        for (spark in sparks) spark.active = false
        pointers.clear()
        joyId = -1
        moveX = 0f
        moveY = 0f
        pressed[0] = false
        mode = Mode.PLAYING
        paused = false
        sound.setEngineActive(false)
        startWave(1)
        sound.play(Sfx.CLICK)
        sound.resume()
    }

    private fun startWave(number: Int) {
        wave = number
        waveActive = true
        spawnLeft = min(46, 5 + number * 2)
        spawnTimer = 0.7f
        clearTimer = 0f
        message = if (number % 5 == 0) "БОСС: КОРОЛЬ ШЕРШНЕЙ!" else "ВОЛНА $number"
        messageTime = 2.5f
        if (number % 5 == 0) spawnEnemy(3)
        sound.play(if (number % 5 == 0) Sfx.BOSS else Sfx.WAVE)
    }

    private fun tryDash() {
        if (player.dashCd > 0f) return
        player.dash = 0.38f
        player.dashCd = max(2.2f, 6f - progress.speed * 0.45f)
        player.invuln = 0.55f
        burst(player.x, player.y, player.z, 20, 220f, 5f, Color.parseColor("#B8F3FF"))
        sound.play(Sfx.DASH)
    }

    private fun tryUlt() {
        if (mode != Mode.PLAYING || paused || ult < 100f) return
        ult = 0f
        ultTime = 3.2f
        ultTick = 0f
        message = "ГНЕВ РОЯ!"
        messageTime = 2f
        shake = 1.6f
        sound.play(Sfx.ULT)
        burst(player.x, player.y, player.z, 40, 300f, 7f, Color.parseColor("#FFE45C"))
    }

    private fun buy(kind: Int) {
        if (progress.buy(kind)) {
            message = "УЛУЧШЕНИЕ КУПЛЕНО!"
            messageTime = 1.6f
            sound.play(Sfx.CLICK)
        } else {
            sound.play(Sfx.HIT)
        }
    }

    private fun project(x: Float, y: Float, z: Float) {
        px = vw * 0.5f + (x - camX) * zoom
        gy = vh * 0.56f + (z - camZ) * zoom * 0.68f
        py = gy - y * zoom * 0.82f
    }

    private fun drawBackdrop(c: Canvas) {
        if (vw <= 0f || vh <= 0f) return
        c.drawRect(0f, 0f, vw, vh, skyPaint)

        val sunX = vw * 0.79f
        val sunY = horizon * 0.38f
        val sunR = 42f * s
        fill.color = Color.argb(70, 255, 245, 170)
        oval(c, sunX, sunY, sunR * 2.1f, sunR * 2.1f, fill)
        fill.color = Color.parseColor("#FFF4A0")
        oval(c, sunX, sunY, sunR, sunR, fill)
        fill.color = Color.parseColor("#FFFFFF")
        oval(c, sunX - sunR * 0.3f, sunY - sunR * 0.3f, sunR * 0.35f, sunR * 0.35f, fill)

        for (i in 0 until 5) {
            val x = ((i * 271f + time * 16f) % (vw + 260f)) - 130f
            val y = horizon * (0.22f + (i % 3) * 0.17f)
            drawCloud(c, x, y, (54f + (i % 2) * 20f) * s)
        }

        path.reset()
        path.moveTo(0f, horizon + 2f)
        for (x in 0..vw.toInt() step 40) {
            path.lineTo(x.toFloat(), horizon - 18f * s - sin(x * 0.006f + time * 0.08f) * 16f * s)
        }
        path.lineTo(vw, horizon + 2f)
        path.close()
        fill.color = Color.parseColor("#79C86A")
        c.drawPath(path, fill)

        c.drawRect(0f, horizon, vw, vh, meadowPaint)
        stroke.strokeWidth = 1.4f * s
        for (i in 1..9) {
            val fraction = i / 9f
            val y = horizon + (vh - horizon) * fraction * fraction
            stroke.color = Color.argb(40, 255, 255, 200)
            c.drawLine(0f, y, vw, y, stroke)
        }
        stroke.strokeWidth = 1.1f * s
        for (i in 0 until 36) {
            val x = (i * 197f) % vw
            val fraction = ((i * 53) % 100) / 100f
            val y = horizon + (vh - horizon) * fraction * fraction
            val length = (7f + (i % 5) * 3f) * s * (0.5f + fraction)
            stroke.color = Color.argb(80, 32, 112, 38)
            c.drawLine(x, y, x - 3f * s, y - length, stroke)
            c.drawLine(x, y, x + 4f * s, y - length * 0.8f, stroke)
        }
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float, size: Float) {
        fill.color = Color.argb(220, 255, 255, 255)
        oval(c, x, y, size * 0.48f, size * 0.30f, fill)
        oval(c, x + size * 0.38f, y + size * 0.05f, size * 0.40f, size * 0.25f, fill)
        oval(c, x - size * 0.36f, y + size * 0.06f, size * 0.35f, size * 0.22f, fill)
        oval(c, x + size * 0.05f, y - size * 0.18f, size * 0.34f, size * 0.26f, fill)
    }

    private fun drawField(c: Canvas) {
        for (flower in flowers) {
            project(flower.x, 0f, flower.z)
            if (px < -140f * s || px > vw + 140f * s || gy < horizon - 60f * s || gy > vh + 100f * s) continue
            drawFlower(c, flower)
        }

        drawQueue.clear()
        drawQueue.add(hiveMarker)
        drawQueue.add(player)
        drawQueue.addAll(allies)
        drawQueue.addAll(enemies)
        drawQueue.sortBy { it.z }
        for (actor in drawQueue) drawActor(c, actor)

        for (bullet in bullets) drawBullet(c, bullet)
        for (pollen in pollens) drawPollen(c, pollen)
        for (spark in sparks) if (spark.active) drawSpark(c, spark)
        for (note in notes) drawNote(c, note)
    }

    private fun drawFlower(c: Canvas, flower: FlowerState) {
        val scale = zoom * (0.8f + (flower.z - camZ) * 0.0003f).coerceIn(0.6f, 1.5f)
        drawShadow(c, px, gy, 16f * scale)
        stroke.color = Color.parseColor("#3D9B36")
        stroke.strokeWidth = 3.5f * scale
        c.drawLine(px, gy, px + sin(flower.phase) * 3f * scale, gy - 33f * scale, stroke)
        val centerY = gy - 35f * scale
        val petalColor = if (flower.bloom > 0.05f) Color.parseColor("#FFF0F7") else Color.parseColor("#FF9FD0")
        fill.color = petalColor
        for (i in 0 until 6) {
            val angle = i * 1.0472f + flower.phase * 0.1f
            oval(c, px + cos(angle) * 10f * scale, centerY + sin(angle) * 8f * scale, 7f * scale, 6f * scale, fill)
        }
        fill.color = Color.parseColor("#FFB52E")
        oval(c, px, centerY, 6f * scale, 5f * scale, fill)
        if (flower.bloom > 0.05f) {
            fill.color = Color.argb(120, 255, 240, 130)
            oval(c, px, centerY, 18f * scale * flower.bloom, 15f * scale * flower.bloom, fill)
        }
    }

    private fun drawActor(c: Canvas, actor: Actor) {
        project(actor.x, actor.y, actor.z)
        val scale = zoom * (1f + (actor.z - camZ) * 0.00022f).coerceIn(0.75f, 1.35f)
        if (actor.kind == 9) {
            drawHive(c, scale)
            return
        }
        drawShadow(c, px, gy, actor.radius * scale)
        when {
            actor === player -> {
                val skin = skins[progress.skin]
                drawBee(c, px, py, scale * 1.25f, skin.body, skin.dark, skin.light, skin.wing, actor.yaw, actor.phase, actor.flash > 0f)
                if (player.dash > 0f || player.invuln > 0f) {
                    stroke.color = Color.argb(160, 140, 235, 255)
                    stroke.strokeWidth = 3f * s
                    oval(c, px, py, 30f * scale, 24f * scale, stroke)
                }
            }
            actor.ally -> drawBee(c, px, py, scale * 0.82f, Color.parseColor("#FFD84A"), Color.parseColor("#704000"), Color.parseColor("#FFF3B0"), Color.parseColor("#D8F6FF"), actor.yaw, actor.phase, false)
            else -> drawEnemy(c, actor, scale)
        }
        if (!actor.ally && actor !== player && actor.hp < actor.maxHp) {
            drawHealth(c, px, py - actor.radius * scale - 26f * s, actor, scale)
        }
    }

    private fun drawBee(
        c: Canvas, x: Float, y: Float, scale: Float,
        body: Int, dark: Int, light: Int, wing: Int,
        yaw: Float, phase: Float, flash: Boolean
    ) {
        val r = 21f * scale
        c.save()
        c.translate(x, y)
        val flap = sin(phase) * r * 0.35f
        fill.color = Color.argb(120, Color.red(wing), Color.green(wing), Color.blue(wing))
        oval(c, -r * 0.25f, -r * 0.85f - flap, r * 0.72f, r * 0.42f, fill)
        oval(c, r * 0.35f, -r * 0.9f + flap, r * 0.62f, r * 0.38f, fill)

        fill.color = dark
        oval(c, 0f, 0f, r * 1.42f, r * 0.88f, fill)
        fill.color = if (flash) Color.WHITE else body
        oval(c, 0f, -r * 0.08f, r * 1.32f, r * 0.78f, fill)
        stroke.color = dark
        stroke.strokeWidth = max(1.5f, 2.5f * scale)
        for (i in -1..1) {
            val lineX = i * r * 0.5f
            c.drawLine(lineX, -r * 0.7f, lineX, r * 0.55f, stroke)
        }
        val headX = sin(yaw) * r * 1.2f
        val headY = -cos(yaw) * r * 0.7f
        fill.color = dark
        oval(c, headX, headY, r * 0.62f, r * 0.58f, fill)
        fill.color = light
        oval(c, headX, headY - r * 0.05f, r * 0.54f, r * 0.5f, fill)
        val eyeOffset = cos(yaw) * r * 0.24f
        val eyeY = headY + sin(yaw) * r * 0.2f
        fill.color = Color.WHITE
        oval(c, headX + eyeOffset, eyeY, r * 0.16f, r * 0.18f, fill)
        oval(c, headX - eyeOffset, eyeY, r * 0.16f, r * 0.18f, fill)
        fill.color = Color.parseColor("#2A1A10")
        oval(c, headX + eyeOffset, eyeY, r * 0.07f, r * 0.08f, fill)
        oval(c, headX - eyeOffset, eyeY, r * 0.07f, r * 0.08f, fill)
        fill.color = dark
        oval(c, -sin(yaw) * r * 1.35f, cos(yaw) * r * 0.75f, r * 0.14f, r * 0.14f, fill)
        c.restore()
    }

    private fun drawEnemy(c: Canvas, enemy: Actor, scale: Float) {
        val r = enemy.radius * scale
        val flash = enemy.flash > 0f
        c.save()
        c.translate(px, py)
        when (enemy.kind) {
            0 -> {
                fill.color = if (flash) Color.WHITE else Color.parseColor("#5E4030")
                oval(c, 0f, 0f, r * 1.15f, r * 0.85f, fill)
                fill.color = if (flash) Color.WHITE else Color.parseColor("#8B5E3C")
                oval(c, 0f, -r * 0.1f, r * 0.95f, r * 0.68f, fill)
                stroke.color = Color.parseColor("#3B2418")
                stroke.strokeWidth = 2f * s
                c.drawLine(0f, -r * 0.75f, 0f, r * 0.6f, stroke)
                fill.color = Color.parseColor("#D94A3A")
                oval(c, sin(enemy.yaw) * r * 0.9f, -cos(enemy.yaw) * r * 0.5f, r * 0.22f, r * 0.2f, fill)
            }
            1 -> {
                stroke.color = Color.parseColor("#2E1A14")
                stroke.strokeWidth = 3f * s
                for (i in -3..3) {
                    val angle = enemy.yaw + i * 0.28f
                    c.drawLine(0f, 0f, sin(angle) * r * 1.8f, cos(angle) * r * 1.4f, stroke)
                }
                fill.color = if (flash) Color.WHITE else Color.parseColor("#4A2E24")
                oval(c, 0f, -r * 0.25f, r, r * 0.8f, fill)
                fill.color = if (flash) Color.WHITE else Color.parseColor("#745045")
                oval(c, 0f, r * 0.25f, r * 0.75f, r * 0.62f, fill)
                fill.color = Color.parseColor("#FF4D4D")
                oval(c, -r * 0.28f, -r * 0.5f, r * 0.12f, r * 0.12f, fill)
                oval(c, r * 0.28f, -r * 0.5f, r * 0.12f, r * 0.12f, fill)
            }
            2 -> {
                val flap = sin(enemy.phase) * r * 0.3f
                fill.color = Color.argb(130, 220, 240, 255)
                oval(c, -r * 0.3f, -r - flap, r * 0.7f, r * 0.35f, fill)
                oval(c, r * 0.3f, -r + flap, r * 0.7f, r * 0.35f, fill)
                fill.color = if (flash) Color.WHITE else Color.parseColor("#F7B500")
                oval(c, 0f, 0f, r * 1.1f, r * 0.7f, fill)
                stroke.color = Color.parseColor("#2B1A08")
                stroke.strokeWidth = 3f * s
                for (i in -1..1) c.drawLine(i * r * 0.45f, -r * 0.6f, i * r * 0.45f, r * 0.55f, stroke)
                fill.color = Color.parseColor("#2B1A08")
                oval(c, sin(enemy.yaw) * r, -cos(enemy.yaw) * r * 0.5f, r * 0.34f, r * 0.32f, fill)
            }
            else -> {
                fill.color = Color.argb(150, 255, 110, 40)
                oval(c, 0f, 0f, r * 1.35f, r * 1.05f, fill)
                fill.color = if (flash) Color.WHITE else Color.parseColor("#D93B18")
                oval(c, 0f, 0f, r, r * 0.8f, fill)
                fill.color = Color.parseColor("#FFD34A")
                for (i in -2..2) oval(c, i * r * 0.34f, 0f, r * 0.1f, r * 0.62f, fill)
                fill.color = Color.parseColor("#3A1200")
                oval(c, sin(enemy.yaw) * r, -cos(enemy.yaw) * r * 0.6f, r * 0.4f, r * 0.35f, fill)
                fill.color = Color.parseColor("#FFE04A")
                path.reset()
                path.moveTo(-r * 0.6f, -r * 1.1f)
                path.lineTo(-r * 0.3f, -r * 1.6f)
                path.lineTo(0f, -r * 1.2f)
                path.lineTo(r * 0.3f, -r * 1.6f)
                path.lineTo(r * 0.6f, -r * 1.1f)
                path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()
    }

    private fun drawHive(c: Canvas, scale: Float) {
        project(0f, 0f, 0f)
        val r = 104f * scale
        drawShadow(c, px, gy, r * 1.25f)
        fill.color = Color.parseColor("#A85C00")
        oval(c, px, gy - 6f * scale, r * 0.95f, r * 0.34f, fill)
        for (i in 0 until 6) {
            val layerY = py - (12f + i * 25f) * scale
            val layerR = r - i * 9f * scale
            fill.color = Color.parseColor("#D98A05")
            oval(c, px, layerY + 7f * scale, layerR, layerR * 0.34f, fill)
            fill.color = if (i % 2 == 0) Color.parseColor("#FFD24A") else Color.parseColor("#FFBE2E")
            oval(c, px, layerY, layerR, layerR * 0.31f, fill)
            stroke.color = Color.argb(120, 145, 78, 0)
            stroke.strokeWidth = 1.6f * s
            for (k in -2..2) {
                val cellX = px + k * layerR * 0.32f
                oval(c, cellX, layerY, 5f * scale, 4f * scale, stroke)
            }
        }
        fill.color = Color.parseColor("#6E3A00")
        oval(c, px, py - 8f * scale, r * 0.21f, r * 0.3f, fill)
        fill.color = Color.parseColor("#FFEC8A")
        oval(c, px, py - 14f * scale, r * 0.12f, r * 0.12f, fill)
        fill.color = Color.parseColor("#FFD84A")
        val crownY = py - 190f * scale
        path.reset()
        path.moveTo(px - 30f * scale, crownY + 20f * scale)
        path.lineTo(px - 20f * scale, crownY - 14f * scale)
        path.lineTo(px, crownY + 6f * scale)
        path.lineTo(px + 20f * scale, crownY - 14f * scale)
        path.lineTo(px + 30f * scale, crownY + 20f * scale)
        path.close()
        c.drawPath(path, fill)
        fill.color = Color.parseColor("#FFF0A0")
        oval(c, px, crownY + 12f * scale, 5f * scale, 5f * scale, fill)
    }

    private fun drawShadow(c: Canvas, x: Float, y: Float, radius: Float) {
        fill.color = Color.argb(65, 24, 56, 18)
        oval(c, x, y, radius, radius * 0.38f, fill)
    }

    private fun drawHealth(c: Canvas, x: Float, y: Float, actor: Actor, scale: Float) {
        val width = 50f * scale
        val height = 7f * scale
        tmp.set(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
        fill.color = Color.argb(180, 30, 20, 12)
        c.drawRoundRect(tmp, height, height, fill)
        val ratio = (actor.hp / actor.maxHp).coerceIn(0f, 1f)
        tmp.set(x - width / 2f, y - height / 2f, x - width / 2f + width * ratio, y + height / 2f)
        fill.color = if (ratio > 0.4f) Color.parseColor("#7DE03A") else Color.parseColor("#FF5A3A")
        c.drawRoundRect(tmp, height, height, fill)
    }

    private fun drawBullet(c: Canvas, bullet: Bullet) {
        project(bullet.x, bullet.y, bullet.z)
        val r = (if (bullet.kind == 2) 8f else 6f) * zoom
        fill.color = if (bullet.friendly) Color.parseColor("#FFF6A0") else Color.parseColor("#FF6B5A")
        oval(c, px, py, r * 1.8f, r * 1.8f, fill)
        fill.color = Color.WHITE
        oval(c, px, py, r, r, fill)
    }

    private fun drawPollen(c: Canvas, pollen: Pollen) {
        project(pollen.x, pollen.y, pollen.z)
        val pulse = 1f + sin(pollen.phase) * 0.18f
        fill.color = Color.argb(90, 255, 232, 120)
        oval(c, px, py, 16f * zoom * pulse, 14f * zoom * pulse, fill)
        fill.color = Color.parseColor("#FFD83C")
        oval(c, px, py, 7f * zoom, 6f * zoom, fill)
        fill.color = Color.WHITE
        oval(c, px - 2f * zoom, py - 2f * zoom, 2.5f * zoom, 2.5f * zoom, fill)
    }

    private fun drawSpark(c: Canvas, spark: Spark) {
        project(spark.x, spark.y, spark.z)
        val alpha = ((spark.life / spark.maxLife).coerceIn(0f, 1f) * 230f).toInt()
        fill.color = Color.argb(alpha, Color.red(spark.color), Color.green(spark.color), Color.blue(spark.color))
        oval(c, px, py, spark.size * zoom, spark.size * zoom, fill)
    }

    private fun drawNote(c: Canvas, note: Note) {
        project(note.x, note.y, note.z)
        write(c, note.text, px, py, 17f, note.color, true)
    }

    private fun drawHud(c: Canvas) {
        val top = 14f * s
        panel(c, top, top, 250f * s, 92f * s)
        write(c, "ВОЛНА $wave", top + 16f * s, top + 30f * s, 20f, Color.parseColor("#FFE45C"), false)
        write(c, "ОЧКИ $score", top + 16f * s, top + 58f * s, 15f, Color.WHITE, false)
        write(c, "ВРАГОВ ${spawnLeft + enemies.size}", top + 16f * s, top + 82f * s, 14f, Color.parseColor("#D8F6FF"), false)

        panel(c, vw / 2f - 200f * s, top, 400f * s, 66f * s)
        write(c, "ЗОЛОТОЙ УЛЕЙ", vw / 2f, top + 24f * s, 16f, Color.parseColor("#FFE45C"))
        bar(c, vw / 2f - 176f * s, top + 36f * s, 352f * s, 16f * s, hiveHp / hiveMax, Color.parseColor("#FFC22E"))
        write(c, "${hiveHp.toInt()} / ${hiveMax.toInt()}", vw / 2f, top + 56f * s, 12f, Color.WHITE)

        panel(c, vw - 258f * s, top, 186f * s, 92f * s)
        write(c, "МЁД: ${progress.honey}", vw - 244f * s, top + 30f * s, 19f, Color.parseColor("#FFE45C"), false)
        write(c, "РЕКОРД: ${progress.bestWave}", vw - 244f * s, top + 58f * s, 14f, Color.WHITE, false)
        write(c, "ЖИЗНЬ: ${player.hp.toInt()}", vw - 244f * s, top + 82f * s, 13f, Color.parseColor("#FF9A8A"), false)

        bar(c, vw / 2f - 100f * s, vh - 58f * s, 200f * s, 11f * s, ult / 100f, Color.parseColor("#FF8A2A"))
        write(c, "ГНЕВ РОЯ ${ult.toInt()}%", vw / 2f, vh - 64f * s, 12f, Color.WHITE)

        if (messageTime > 0f && message.isNotEmpty()) {
            val width = min(vw * 0.7f, 520f * s)
            tmp.set(vw / 2f - width / 2f, vh * 0.13f, vw / 2f + width / 2f, vh * 0.13f + 50f * s)
            fill.color = Color.argb(200, 80, 32, 0)
            c.drawRoundRect(tmp, 16f * s, 16f * s, fill)
            write(c, message, vw / 2f, vh * 0.13f + 34f * s, 20f, Color.parseColor("#FFE45C"))
        }

        drawJoystick(c)
        actionButton(c, stingRect, "ЖАЛО", Color.parseColor("#FFB32E"), 0f, pressed[0])
        actionButton(c, dashRect, "РЫВОК", Color.parseColor("#36B7D8"), player.dashCd / max(1f, tryDashCooldown()))
        actionButton(c, ultRect, "ГНЕВ РОЯ", Color.parseColor("#E0562A"), 1f - ult / 100f, ultTime > 0f)
        actionButton(c, pauseRect, "II", Color.parseColor("#7A5A2A"), 0f, paused)
    }

    private fun tryDashCooldown(): Float = max(2.2f, 6f - progress.speed * 0.45f)

    private fun drawJoystick(c: Canvas) {
        val baseX = if (joyId >= 0) joyX else vw * 0.14f
        val baseY = if (joyId >= 0) joyY else vh * 0.74f
        val radius = 64f * s
        fill.color = Color.argb(80, 20, 60, 20)
        oval(c, baseX, baseY, radius, radius, fill)
        stroke.color = Color.argb(180, 255, 235, 160)
        stroke.strokeWidth = 3f * s
        oval(c, baseX, baseY, radius, radius, stroke)
        val knobX = baseX + moveX * radius * 0.5f
        val knobY = baseY - moveY * radius * 0.5f
        fill.color = Color.argb(210, 255, 208, 76)
        oval(c, knobX, knobY, radius * 0.42f, radius * 0.42f, fill)
        fill.color = Color.argb(190, 255, 255, 255)
        oval(c, knobX - radius * 0.1f, knobY - radius * 0.12f, radius * 0.16f, radius * 0.12f, fill)
    }

    private fun actionButton(c: Canvas, rect: RectF, label: String, color: Int, cooldown: Float, active: Boolean) {
        tmp.set(rect.left, rect.top + 4f * s, rect.right, rect.bottom + 4f * s)
        fill.color = Color.parseColor("#5A3200")
        c.drawRoundRect(tmp, 18f * s, 18f * s, fill)
        tmp.set(rect.left, rect.top, rect.right, rect.bottom)
        fill.color = if (active) lighten(color) else color
        c.drawRoundRect(tmp, 18f * s, 18f * s, fill)
        stroke.color = Color.argb(190, 255, 246, 200)
        stroke.strokeWidth = 2f * s
        c.drawRoundRect(tmp, 18f * s, 18f * s, stroke)
        if (cooldown > 0.02f) {
            fill.color = Color.argb(140, 30, 20, 10)
            tmp.set(rect.left, rect.top, rect.right, rect.top + rect.height() * cooldown.coerceIn(0f, 1f))
            c.drawRoundRect(tmp, 18f * s, 18f * s, fill)
        }
        val fontSize = if (label.length > 5) 13f else 16f
        write(c, label, rect.centerX(), rect.centerY() + 5f * s, fontSize, Color.WHITE)
    }

    private fun panel(c: Canvas, x: Float, y: Float, width: Float, height: Float) {
        tmp.set(x, y, x + width, y + height)
        fill.color = Color.argb(180, 42, 34, 12)
        c.drawRoundRect(tmp, 14f * s, 14f * s, fill)
        stroke.color = Color.argb(190, 255, 205, 88)
        stroke.strokeWidth = 2f * s
        c.drawRoundRect(tmp, 14f * s, 14f * s, stroke)
    }

    private fun bar(c: Canvas, x: Float, y: Float, width: Float, height: Float, ratio: Float, color: Int) {
        tmp.set(x, y, x + width, y + height)
        fill.color = Color.argb(190, 30, 24, 12)
        c.drawRoundRect(tmp, height / 2f, height / 2f, fill)
        tmp.set(x, y, x + width * ratio.coerceIn(0f, 1f), y + height)
        fill.color = color
        c.drawRoundRect(tmp, height / 2f, height / 2f, fill)
    }

    private fun drawMenu(c: Canvas) {
        dim(c, 0.28f)
        textBold.textSize = 42f * s
        textBold.color = Color.parseColor("#7A2E00")
        textBold.textAlign = Paint.Align.CENTER
        c.drawText("КТО ПЧЁЛОК УВАЖАЕТ", vw / 2f + 3f * s, vh * 0.13f + 3f * s, textBold)
        write(c, "КТО ПЧЁЛОК УВАЖАЕТ", vw / 2f, vh * 0.13f, 42f, Color.parseColor("#FFF0A0"))
        write(c, "СОБИРАЙ МЁД · ЗАЩИЩАЙ УЛЕЙ · УВАЖАЙ ПЧЁЛОК", vw / 2f, vh * 0.19f, 16f, Color.WHITE)

        for (i in 0..3) drawSkinCard(c, skinRects[i], i)
        for (i in 0..3) drawUpgradeCard(c, upgradeRects[i], i, false)
        actionButton(c, playRect, "ИГРАТЬ!", Color.parseColor("#E8A31B"), 0f, false)
        actionButton(c, soundRect, if (soundOn) "ЗВУК" else "ТИШО", Color.parseColor("#4F8FB8"), 0f, false)
        write(c, "МЁД: ${progress.honey}    ЛУЧШАЯ ВОЛНА: ${progress.bestWave}", vw / 2f, vh * 0.975f, 15f, Color.WHITE)
    }

    private fun drawSkinCard(c: Canvas, rect: RectF, index: Int) {
        val skin = skins[index]
        val unlocked = progress.unlocked(index)
        tmp.set(rect.left, rect.top, rect.right, rect.bottom)
        fill.color = if (progress.skin == index) Color.argb(225, 120, 78, 8) else Color.argb(190, 36, 52, 24)
        c.drawRoundRect(tmp, 14f * s, 14f * s, fill)
        stroke.color = if (progress.skin == index) Color.parseColor("#FFE45C") else Color.argb(150, 255, 220, 120)
        stroke.strokeWidth = (if (progress.skin == index) 3f else 1.5f) * s
        c.drawRoundRect(tmp, 14f * s, 14f * s, stroke)
        drawBee(c, rect.centerX(), rect.top + 40f * s, 0.9f * s, skin.body, skin.dark, skin.light, skin.wing, 0f, time * 12f + index, !unlocked)
        write(c, skin.name, rect.centerX(), rect.bottom - 32f * s, 13f, Color.WHITE)
        write(c, if (unlocked) if (progress.skin == index) "ВЫБРАН" else "ВЫБРАТЬ" else "НУЖНО ${skin.need} МЁДА", rect.centerX(), rect.bottom - 13f * s, 11f, if (unlocked) Color.parseColor("#FFE45C") else Color.parseColor("#FF9A8A"))
    }

    private fun drawUpgradeCard(c: Canvas, rect: RectF, kind: Int, shop: Boolean) {
        val level = progress.level(kind)
        val maxLevel = progress.maxLevel(kind)
        tmp.set(rect.left, rect.top, rect.right, rect.bottom)
        fill.color = Color.argb(205, 40, 54, 26)
        c.drawRoundRect(tmp, 14f * s, 14f * s, fill)
        stroke.color = Color.argb(180, 255, 208, 92)
        stroke.strokeWidth = 2f * s
        c.drawRoundRect(tmp, 14f * s, 14f * s, stroke)
        write(c, upgradeNames[kind], rect.centerX(), rect.top + 27f * s, if (shop) 17f else 15f, Color.parseColor("#FFF0A0"))
        write(c, "УРОВЕНЬ $level / $maxLevel", rect.centerX(), rect.top + 53f * s, 13f, Color.WHITE)
        bar(c, rect.left + 18f * s, rect.top + 62f * s, rect.width() - 36f * s, 10f * s, level.toFloat() / maxLevel, Color.parseColor("#7DE03A"))
        write(c, if (level >= maxLevel) "МАКСИМУМ" else "${progress.cost(kind)} МЁДА", rect.centerX(), rect.bottom - 14f * s, 13f, if (progress.honey >= progress.cost(kind) && level < maxLevel) Color.parseColor("#FFE45C") else Color.parseColor("#FFB3A0"))
    }

    private fun drawShop(c: Canvas) {
        dim(c, 0.66f)
        panel(c, vw / 2f - 380f * s, vh * 0.12f, 760f * s, vh * 0.78f)
        write(c, "ВОЛНА $wave ЗАЧИЩЕНА!", vw / 2f, vh * 0.22f, 30f, Color.parseColor("#FFF0A0"))
        write(c, "+${14 + wave * 4} МЁДА · РЕКОРД: ВОЛНА ${progress.bestWave}", vw / 2f, vh * 0.29f, 17f, Color.WHITE)
        for (i in 0..3) drawUpgradeCard(c, shopRects[i], i, true)
        actionButton(c, nextRect, "ДАЛЬШЕ", Color.parseColor("#E8A31B"), 0f, false)
        actionButton(c, shopMenuRect, "МЕНЮ", Color.parseColor("#4F8FB8"), 0f, false)
        write(c, "МЁД: ${progress.honey}", vw / 2f, vh * 0.78f, 18f, Color.parseColor("#FFE45C"))
    }

    private fun drawGameOver(c: Canvas) {
        dim(c, 0.7f)
        panel(c, vw / 2f - 380f * s, vh * 0.18f, 760f * s, vh * 0.66f)
        write(c, "УЛЕЙ ПАЛ", vw / 2f, vh * 0.34f, 42f, Color.parseColor("#FF7A52"))
        write(c, "ВОЛНА $wave · ОЧКИ $score", vw / 2f, vh * 0.45f, 23f, Color.WHITE)
        write(c, "СОБРАНО МЁДА: ${progress.honey} · РЕКОРД: ${progress.bestWave}", vw / 2f, vh * 0.54f, 17f, Color.parseColor("#FFE45C"))
        actionButton(c, restartRect, "ЕЩЁ РАЗ", Color.parseColor("#E8A31B"), 0f, false)
        actionButton(c, menuRect, "В МЕНЮ", Color.parseColor("#4F8FB8"), 0f, false)
    }

    private fun drawPause(c: Canvas) {
        dim(c, 0.7f)
        panel(c, vw / 2f - 260f * s, vh * 0.48f, 520f * s, vh * 0.34f)
        write(c, "ПАУЗА", vw / 2f, vh * 0.56f, 36f, Color.parseColor("#FFF0A0"))
        actionButton(c, resumeRect, "ПРОДОЛЖИТЬ", Color.parseColor("#E8A31B"), 0f, false)
        actionButton(c, quitRect, "В МЕНЮ", Color.parseColor("#4F8FB8"), 0f, false)
    }

    private fun dim(c: Canvas, alpha: Float) {
        fill.color = Color.argb((alpha * 255f).toInt(), 12, 20, 8)
        c.drawRect(0f, 0f, vw, vh, fill)
    }

    private fun write(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, center: Boolean = true) {
        textBold.color = color
        textBold.textSize = size * s
        textBold.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(value, x, y, textBold)
    }

    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, paint: Paint) {
        tmp.set(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(tmp, paint)
    }

    private fun lighten(color: Int): Int {
        return Color.rgb(
            min(255, Color.red(color) + 40),
            min(255, Color.green(color) + 40),
            min(255, Color.blue(color) + 40)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!soundStarted) {
            soundStarted = true
            sound.start()
            sound.resume()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event, event.actionIndex)
            MotionEvent.ACTION_POINTER_DOWN -> handleDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (pointers[id]) {
                        1 -> if (id == joyId) updateJoystick(x, y)
                        2 -> pressed[0] = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> handleUp(event, event.actionIndex)
        }
        invalidate()
        return true
    }

    private fun handleDown(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        when (mode) {
            Mode.MENU -> {
                when {
                    hit(soundRect, x, y) -> {
                        soundOn = !soundOn
                        progress.sound = soundOn
                        sound.setEnabled(soundOn)
                        sound.play(Sfx.CLICK)
                    }
                    hit(playRect, x, y) -> startGame()
                    else -> {
                        for (i in 0..3) {
                            if (hit(skinRects[i], x, y) && progress.unlocked(i)) {
                                progress.skin = i
                                sound.play(Sfx.CLICK)
                                return
                            }
                        }
                        for (i in 0..3) if (hit(upgradeRects[i], x, y)) buy(i)
                    }
                }
                return
            }
            Mode.SHOP -> {
                when {
                    hit(nextRect, x, y) -> {
                        mode = Mode.PLAYING
                        startWave(wave + 1)
                        sound.play(Sfx.CLICK)
                    }
                    hit(shopMenuRect, x, y) -> toMenu()
                    else -> for (i in 0..3) if (hit(shopRects[i], x, y)) buy(i)
                }
                return
            }
            Mode.GAMEOVER -> {
                when {
                    hit(restartRect, x, y) -> startGame()
                    hit(menuRect, x, y) -> toMenu()
                }
                return
            }
            Mode.PLAYING -> {
                if (paused) {
                    when {
                        hit(resumeRect, x, y) -> {
                            paused = false
                            sound.resume()
                            sound.play(Sfx.CLICK)
                        }
                        hit(quitRect, x, y) -> toMenu()
                    }
                    return
                }
                when {
                    hit(pauseRect, x, y) -> {
                        paused = true
                        pressed[0] = false
                        pointers.clear()
                        joyId = -1
                        moveX = 0f
                        moveY = 0f
                        sound.setEngineActive(false)
                        sound.pause()
                    }
                    hit(ultRect, x, y) -> {
                        tryUlt()
                        pointers[id] = 5
                    }
                    hit(dashRect, x, y) -> {
                        tryDash()
                        pointers[id] = 4
                    }
                    hit(stingRect, x, y) -> {
                        pressed[0] = true
                        pointers[id] = 2
                    }
                    x < vw * 0.5f && joyId < 0 -> {
                        joyId = id
                        joyX = x
                        joyY = y
                        pointers[id] = 1
                        updateJoystick(x, y)
                    }
                    else -> {
                        pressed[0] = true
                        pointers[id] = 2
                    }
                }
            }
        }
    }

    private fun handleUp(event: MotionEvent, index: Int) {
        val action = pointers.remove(event.getPointerId(index)) ?: -1
        if (action == 1) {
            joyId = -1
            moveX = 0f
            moveY = 0f
        }
        if (action == 2) pressed[0] = false
    }

    private fun updateJoystick(x: Float, y: Float) {
        val radius = 64f * s
        val dx = x - joyX
        val dy = y - joyY
        val distance = hypot(dx, dy)
        if (distance <= 4f * s) {
            moveX = 0f
            moveY = 0f
            return
        }
        val strength = min(1f, distance / radius)
        moveX = dx / distance * strength
        moveY = -dy / distance * strength
    }

    private fun hit(rect: RectF, x: Float, y: Float): Boolean = x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
}
