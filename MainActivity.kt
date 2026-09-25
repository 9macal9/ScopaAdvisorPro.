package com.example.scopaadvisor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val zones = mutableMapOf<Card, Zone>()
    private val buttons = mutableMapOf<Card, Button>()
    private var mode = Zone.HAND
    private lateinit var modeLabel: TextView
    private lateinit var summary: TextView
    private lateinit var result: TextView
    private lateinit var oppCount: SeekBar
    private lateinit var oppCountLabel: TextView
    private lateinit var autoStatus: TextView
    private val captureRequest = 901

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScopaEngine.allCards.forEach { zones[it] = Zone.UNKNOWN }
        setContentView(buildUi())
        refreshAll()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(245, 247, 245))
        }
        root.addView(TextView(this).apply {
            text = "Scopa Advisor Pro 2.0"
            textSize = 25f
            setTextColor(Color.rgb(11, 61, 26))
            setTypeface(typeface, 1)
        })
        root.addView(TextView(this).apply {
            text = "Overlay + cattura schermo + conteggio + simulazione. Il riconoscimento automatico usa un profilo grafico del gioco."
            textSize = 13f; setPadding(0, 6, 0, 10)
        })

        val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        autoRow.addView(Button(this).apply { text = "AVVIA AUTO"; setOnClickListener { startAuto() } })
        autoRow.addView(Button(this).apply { text = "STOP"; setOnClickListener { stopService(Intent(this@MainActivity, ScreenCaptureService::class.java)); autoStatus.text = "AUTO fermato" } })
        root.addView(autoRow)
        autoStatus = TextView(this).apply { textSize = 13f; setPadding(0, 4, 0, 8); text = "AUTO non attivo" }
        root.addView(autoStatus)

        val modeRow = HorizontalScrollView(this)
        val modeInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("In mano" to Zone.HAND, "Sul tavolo" to Zone.TABLE, "Prese mie" to Zone.MINE, "Prese avv." to Zone.OPP, "Cancella" to Zone.UNKNOWN).forEach { (name, z) ->
            modeInner.addView(Button(this).apply { text = name; setOnClickListener { mode = z; modeLabel.text = "Modalità: $name" } })
        }
        modeRow.addView(modeInner); root.addView(modeRow)
        modeLabel = TextView(this).apply { textSize = 14f; setPadding(0, 6, 0, 6) }; root.addView(modeLabel)
        oppCountLabel = TextView(this).apply { textSize = 14f }; root.addView(oppCountLabel)
        oppCount = SeekBar(this).apply {
            max = 2; progress = 2
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { oppCountLabel.text = "Carte avversario in mano: ${p+1}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }; root.addView(oppCount)

        val scroll = ScrollView(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (s in 0..3) {
            wrap.addView(TextView(this).apply { text = suitLabel(s); textSize = 18f; setTypeface(typeface, 1); setPadding(0,10,0,4) })
            val grid = GridLayout(this).apply { columnCount = 5 }
            for (v in 1..10) {
                val c = Card(s,v)
                val b = Button(this).apply {
                    text = "${valueLabel(v)} ${suitShort(s)}"; isAllCaps = false; minWidth = 0; setPadding(4,2,4,2)
                    setOnClickListener { zones[c] = mode; refreshButton(c); refreshSummary() }
                }
                buttons[c] = b
                grid.addView(b, GridLayout.LayoutParams().apply { width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            }
            wrap.addView(grid)
        }
        summary = TextView(this).apply { textSize = 14f; setPadding(0,12,0,8) }; wrap.addView(summary)
        wrap.addView(Button(this).apply { text = "IMPORTA RILEVAMENTO AUTO"; setOnClickListener { importAuto() } })
        wrap.addView(Button(this).apply { text = "ANALIZZA MOSSE"; setOnClickListener { analyze() } })
        wrap.addView(Button(this).apply { text = "RESET"; setOnClickListener { ScopaEngine.allCards.forEach { zones[it] = Zone.UNKNOWN }; result.text = ""; refreshAll() } })
        result = TextView(this).apply { textSize = 16f; setPadding(0,12,0,30) }; wrap.addView(result)
        scroll.addView(wrap); root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun startAuto() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Abilita 'Mostra sopra altre app', poi premi di nuovo AVVIA AUTO", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Deprecated in Android API; kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            autoStatus.text = "AUTO attivo: apri il gioco; l'overlay resterà visibile"
        }
    }

    private fun importAuto() {
        AutoState.hand.forEach { zones[it] = Zone.HAND }
        AutoState.table.forEach { zones[it] = Zone.TABLE }
        refreshAll()
        autoStatus.text = AutoState.status
    }

    private fun refreshAll() { modeLabel.text = "Modalità: In mano"; oppCountLabel.text = "Carte avversario in mano: ${oppCount.progress + 1}"; ScopaEngine.allCards.forEach { refreshButton(it) }; refreshSummary() }
    private fun refreshButton(c: Card) {
        val b = buttons[c] ?: return
        when (zones[c]) {
            Zone.UNKNOWN -> { b.setBackgroundColor(Color.rgb(230,230,230)); b.setTextColor(Color.BLACK) }
            Zone.HAND -> { b.setBackgroundColor(Color.rgb(25,118,210)); b.setTextColor(Color.WHITE) }
            Zone.TABLE -> { b.setBackgroundColor(Color.rgb(255,193,7)); b.setTextColor(Color.BLACK) }
            Zone.MINE -> { b.setBackgroundColor(Color.rgb(46,125,50)); b.setTextColor(Color.WHITE) }
            Zone.OPP -> { b.setBackgroundColor(Color.rgb(198,40,40)); b.setTextColor(Color.WHITE) }
            null -> {}
        }
    }
    private fun refreshSummary() { fun n(z: Zone)=zones.values.count{it==z}; summary.text="Mano: ${n(Zone.HAND)} | Tavolo: ${n(Zone.TABLE)} | Prese mie: ${n(Zone.MINE)} | Prese avv.: ${n(Zone.OPP)} | Non viste: ${n(Zone.UNKNOWN)}" }

    private fun analyze() {
        if (zones.values.none { it == Zone.HAND }) { Toast.makeText(this,"Seleziona almeno una carta in mano",Toast.LENGTH_SHORT).show(); return }
        result.text = "Calcolo in corso..."
        Thread {
            val best = ScopaEngine.analyze(zones, oppCount.progress + 1, 600)
            val txt = buildString {
                append("STIMA MONTE CARLO DELLA MANO\n\n")
                best.forEachIndexed { i,c ->
                    append(if(i==0) "★ CONSIGLIO  " else "• ")
                    append("${c.move.card}: ${"%.1f".format(c.winPct*100)}% vittoria")
                    if(c.move.capture.isNotEmpty()) append(" → prende ${c.move.capture.joinToString()}")
                    append("\n")
                }
                append("\nStima basata sulle carte non viste e su una strategia automatica semplificata.")
            }
            runOnUiThread { result.text = txt }
        }.start()
    }
}
