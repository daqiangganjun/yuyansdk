
package com.yuyan.imemodule.ui.fragment.theme

import android.annotation.SuppressLint
import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.MimeTypeMap
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.yuyan.imemodule.libs.cropper.CropImageContract
import com.yuyan.imemodule.libs.cropper.CropImageContractOptions
import com.yuyan.imemodule.libs.cropper.CropImageOptions
import com.yuyan.imemodule.libs.cropper.CropImageView
import com.yuyan.imemodule.libs.cropper.parcelable
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeFilesManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.data.theme.ThemeBackgroundCache
import com.yuyan.imemodule.data.theme.FocalBackgroundDrawable
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DarkenColorFilter
import com.yuyan.imemodule.keyboard.KeyboardPreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.packed
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import splitties.views.gravityVerticalCenter
import splitties.views.horizontalPadding
import splitties.views.textAppearance
import splitties.views.topPadding
import java.io.File

class CustomThemeActivity : AppCompatActivity() {

    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val toolbar by lazy {
        view(::Toolbar) {
            backgroundColor = getColor(R.color.settings_background)
            setTitleTextColor(getColor(R.color.settings_text))
            elevation = dp(1f)
        }
    }

    private lateinit var previewUi: KeyboardPreviewView

    private fun createTextView(@StringRes string: Int? = null, ripple: Boolean = false) = textView {
        if (string != null) {
            setText(string)
        }
        gravity = gravityVerticalCenter
        textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        horizontalPadding = dp(16)
        if (ripple) {
            background = styledDrawable(android.R.attr.selectableItemBackground)
        }
    }
    private val variantLabel by lazy {
        createTextView(R.string.dark_keys, ripple = true)
    }
    private val variantSwitch by lazy {
        MaterialSwitch(this).apply {
            isChecked = false
        }
    }
    private val brightnessLabel by lazy {
        createTextView(R.string.brightness)
    }
    private val brightnessValue by lazy {
        createTextView()
    }
    private val brightnessSeekBar by lazy {
        Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            contentDescription = getString(R.string.brightness)
            setLabelFormatter { "${it.roundToInt()}%" }
        }
    }

    private val keyMaskLabel by lazy { createTextView(R.string.key_background_mask_strength) }
    private val keyMaskValue by lazy { createTextView() }
    private val keyMaskHint by lazy {
        createTextView(R.string.key_background_mask_hint).apply {
            textSize = 13f
            setTextColor(getColor(R.color.settings_secondary_text))
        }
    }
    private val keyMaskSlider by lazy {
        Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            tag = "theme_key_mask"
            contentDescription = getString(R.string.key_background_mask_strength)
            setLabelFormatter { getString(R.string.theme_percentage, it.roundToInt()) }
        }
    }

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }

    private val scrollView by lazy {
        val lineHeight = dp(48)
        val itemMargin = dp(30)
        constraintLayout {
            bottomPadding = dp(24)
            add(previewUi, lParams(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.inputAreaHeight) {
                topOfParent()
                centerHorizontally()
                above(cropLabel, dp(8))
                verticalChainStyle = packed
            })
            add(cropLabel, lParams(matchConstraints, lineHeight) {
                below(previewUi)
                centerHorizontally(itemMargin)
                above(variantLabel)
            })
            add(variantLabel, lParams(matchConstraints, lineHeight) {
                below(cropLabel)
                startOfParent(itemMargin)
                before(variantSwitch)
                above(brightnessLabel)
            })
            add(variantSwitch, lParams(wrapContent, lineHeight) {
                topToTopOf(variantLabel)
                endOfParent(itemMargin)
            })
            add(brightnessLabel, lParams(matchConstraints, lineHeight) {
                below(variantLabel)
                startOfParent(itemMargin)
                before(brightnessValue)
                above(brightnessSeekBar)
            })
            add(brightnessValue, lParams(wrapContent, lineHeight) {
                topToTopOf(brightnessLabel)
                endOfParent(itemMargin)
            })
            add(brightnessSeekBar, lParams(matchConstraints, wrapContent) {
                below(brightnessLabel)
                centerHorizontally(itemMargin)
                above(keyMaskLabel)
            })
            add(keyMaskLabel, lParams(matchConstraints, lineHeight) {
                below(brightnessSeekBar, dp(12))
                startOfParent(itemMargin)
                before(keyMaskValue)
            })
            add(keyMaskValue, lParams(wrapContent, lineHeight) {
                topToTopOf(keyMaskLabel)
                endOfParent(itemMargin)
            })
            add(keyMaskHint, lParams(matchConstraints, wrapContent) {
                below(keyMaskLabel)
                centerHorizontally(itemMargin)
            })
            add(keyMaskSlider, lParams(matchConstraints, wrapContent) {
                below(keyMaskHint, dp(8))
                centerHorizontally(itemMargin)
                bottomOfParent()
            })
        }.wrapInScrollView {
            isFillViewport = true
        }
    }

    private val ui by lazy {
        constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(scrollView, lParams {
                below(toolbar)
                centerHorizontally()
                bottomOfParent()
            })
        }
    }

    private var newCreated = true

    private lateinit var theme: Theme.Custom

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropImageContractOptions>
        var srcImageExtension: String? = null
        var srcImageBuffer: ByteArray? = null
        var tempImageFile: File? = null
        var cropRect: Rect? = null
        lateinit var croppedBitmap: Bitmap
        lateinit var filteredDrawable: Drawable
        var previewSourceFile: File? = null
        var rotation: Int? = null
        var flipHorizontal = false
        var flipVertical = false
        lateinit var srcImageFile: File
        lateinit var croppedImageFile: File
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null)
            block(backgroundStates, theme.backgroundImage!!)
    }

    private fun BackgroundStates.setKeyVariant(background: Theme.Custom.CustomBackground, darkKeys: Boolean) {
        val original = theme
        theme = if (darkKeys)
            ThemePreset.TransparentLight.deriveCustomBackground(
                theme.name,
                background.croppedFilePath,
                background.srcFilePath,
                brightnessSeekBar.value.roundToInt(),
                background.cropRect
            ) else
            ThemePreset.TransparentDark.deriveCustomBackground(
                theme.name,
                background.croppedFilePath,
                background.srcFilePath,
                brightnessSeekBar.value.roundToInt(),
                background.cropRect
            )
        theme = theme.copy(
            backgroundImage = original.backgroundImage,
            keyBackgroundColor = ColorUtils.setAlphaComponent(theme.keyBackgroundColor, Color.alpha(original.keyBackgroundColor)),
            functionKeyBackgroundColor = ColorUtils.setAlphaComponent(theme.functionKeyBackgroundColor, Color.alpha(original.functionKeyBackgroundColor)),
            accentKeyBackgroundColor = ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, Color.alpha(original.accentKeyBackgroundColor)),
        )
        previewUi.updateKeyAppearance(theme)
        updateState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recover from bundle
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            whenHasBackground {
                croppedImageFile = File(it.croppedFilePath)
                srcImageFile = File(it.srcFilePath)
                cropRect = it.cropRect
                rotation = it.rotation
                flipHorizontal = it.flipHorizontal
                flipVertical = it.flipVertical
                croppedBitmap = BitmapFactory.decodeFile(it.croppedFilePath)
                filteredDrawable = BitmapDrawable(resources, croppedBitmap)
            }
            newCreated = false
        }
        // create new
        if (originTheme == null) {
            val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
            backgroundStates.apply {
                croppedImageFile = c
                srcImageFile = s
            }
            theme = ThemePreset.TransparentDark.deriveCustomBackground(n, c.path, s.path)
        }

        previewUi = KeyboardPreviewView(this).apply {
            scaleX = 0.7f
            scaleY = 0.7f
            outlineProvider = ViewOutlineProvider.BOUNDS
            elevation = dp(4f)
        }
        previewUi.updateKeyAppearance(theme)

        if (theme.backgroundImage == null) {
            brightnessLabel.visibility = View.GONE
            brightnessValue.visibility = View.GONE
            cropLabel.visibility = View.GONE
            variantLabel.visibility = View.GONE
            variantSwitch.visibility = View.GONE
            brightnessSeekBar.visibility = View.GONE
        }
        keyMaskSlider.value = theme.keyBackgroundOpacity.toFloat()
        keyMaskValue.text = getString(R.string.theme_percentage, theme.keyBackgroundOpacity)
        keyMaskSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val opacity = value.roundToInt()
                theme = theme.withKeyBackgroundOpacity(opacity)
                keyMaskValue.text = getString(R.string.theme_percentage, opacity)
                previewUi.updateKeyAppearance(theme)
            }
        }
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            ui.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.topPadding = statusBars.top
            scrollView.bottomPadding = navBars.bottom
            windowInsets
        }
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setContentView(ui)
        whenHasBackground { background ->
            brightnessSeekBar.value = background.brightness.coerceIn(0, 100).toFloat()
            variantSwitch.isChecked = !theme.isDark
            launcher = registerForActivityResult(CropImageContract()) {
                if (!it.isSuccessful) {
                    if (newCreated)
                        cancel()
                    else
                        return@registerForActivityResult
                } else {
                    if (newCreated) {
                        srcImageExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(it.originalUri!!))
                        srcImageBuffer = contentResolver.openInputStream(it.originalUri)!!.use { x -> x.readBytes() }
                        previewSourceFile?.delete()
                        previewSourceFile = File.createTempFile("theme-source-", ".image", cacheDir).apply { writeBytes(srcImageBuffer!!) }
                    }
                    cropRect = it.cropRect!!
                    rotation = it.rotation
                    flipHorizontal = it.flipHorizontal
                    flipVertical = it.flipVertical
                    croppedBitmap = it.getBitmap(this@CustomThemeActivity)!!.let { bitmap ->
                        if (android.os.Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
                            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                    }
                    filteredDrawable = BitmapDrawable(resources, croppedBitmap)
                    updateState()
                }
            }
            cropLabel.setOnClickListener {
                launchCrop(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.inputAreaHeight)
            }
            variantLabel.setOnClickListener {
                variantSwitch.isChecked = !variantSwitch.isChecked
            }
            variantSwitch.setOnCheckedChangeListener { _, isChecked ->
                setKeyVariant(background, darkKeys = isChecked)
            }
            brightnessSeekBar.addOnChangeListener { _, _, fromUser ->
                if (fromUser) updateState()
            }
        }
        if (newCreated) {
            cropLabel.visibility = View.GONE
            whenHasBackground {
                launchCrop(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.inputAreaHeight)
            }
        } else {
            if (theme.backgroundImage == null) previewUi.setTheme(theme)
            else whenHasBackground {
                updateState()
                previewUi.setTheme(theme, filteredDrawable)
            }
        }

        onBackPressedDispatcher.addCallback {
            cancel()
        }
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int) {
        if (tempImageFile == null || tempImageFile?.exists() != true) {
            tempImageFile = File.createTempFile("cropped", ".png", cacheDir)
        }
        launcher.launch(
            CropImageContractOptions(
                uri = srcImageFile.takeIf { it.exists() }?.toUri(),
                CropImageOptions(
                    activityTitle = "选择背景中心点",
                    showCropLabel = true,
                    cropperLabelText = "移动选框确定中心，背景随键盘高度展开",
                    cropperLabelTextSize = 14f * resources.displayMetrics.scaledDensity,
                    autoZoomEnabled = false,
                    initialRotation = rotation ?: -1,
                    flipHorizontally = flipHorizontal,
                    flipVertically = flipVertical,
                    initialCropWindowRectangle = cropRect,
                    guidelines = CropImageView.Guidelines.ON_TOUCH,
                    borderLineColor = Color.WHITE,
                    borderLineThickness = dp(1f),
                    borderCornerColor = Color.WHITE,
                    borderCornerOffset = 0f,
                    imageSourceIncludeGallery = true,
                    imageSourceIncludeCamera = false,
                    aspectRatioX = w,
                    aspectRatioY = h,
                    fixAspectRatio = true,
                    outputRequestWidth = w,
                    outputRequestHeight = h,
                    outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_INSIDE,
                    customOutputUri = tempImageFile!!.toUri(),
                    outputCompressFormat = Bitmap.CompressFormat.PNG,
                    cropMenuCropButtonIcon = R.drawable.ic_menu_done,
                    cropMenuCropButtonTitle = "确定中心",
                    showProgressBar = true,
                    progressBarColor = styledColor(android.R.attr.colorAccent),
                    activityMenuIconColor = getColor(R.color.settings_text),
                    activityMenuTextColor = getColor(R.color.settings_text),
                    activityBackgroundColor = styledColor(android.R.attr.colorBackground),
                    toolbarColor = getColor(R.color.settings_background),
                    toolbarTitleColor = getColor(R.color.settings_text),
                    toolbarBackButtonColor = getColor(R.color.settings_text)
                )
            )
        )
    }

    @SuppressLint("SetTextI18n")
    private fun BackgroundStates.updateState() {
        val progress = brightnessSeekBar.value.roundToInt()
        brightnessValue.text = "$progress%"
        val background = theme.backgroundImage!!
        filteredDrawable = background.copy(
            srcFilePath = previewSourceFile?.path ?: srcImageFile.path,
            cropRect = cropRect, brightness = progress, rotation = rotation,
            flipHorizontal = flipHorizontal, flipVertical = flipVertical,
        ).toDrawable() ?: FocalBackgroundDrawable(croppedBitmap).apply { colorFilter = DarkenColorFilter(100 - progress) }
        previewUi.background = filteredDrawable
    }

    private fun cancel() {
        whenHasBackground {
            tempImageFile?.delete()
            previewSourceFile?.let { ThemeBackgroundCache.invalidate(it.path); it.delete() }
        }
        setResult(
            Activity.RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        lifecycleScope.withLoadingDialog(this) {
            whenHasBackground {
                withContext(Dispatchers.IO) {
                    tempImageFile?.delete()
                    croppedImageFile.delete()
                    croppedImageFile.outputStream().use {
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (newCreated) {
                        if (srcImageExtension != null) {
                            srcImageFile = File("${srcImageFile.absolutePath}.$srcImageExtension")
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = srcImageFile.absolutePath
                                )
                            )
                        }
                        srcImageFile.writeBytes(srcImageBuffer!!)
                    }
                }
            }
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    var newTheme = theme
                    whenHasBackground {
                        newTheme = theme.copy(
                            backgroundImage = it.copy(
                                brightness = brightnessSeekBar.value.roundToInt(),
                                cropRect = cropRect,
                                rotation = rotation, flipHorizontal = flipHorizontal, flipVertical = flipVertical,
                            )
                        )
                    }
                    putExtra(
                        RESULT,
                        if (newCreated)
                            BackgroundResult.Created(newTheme)
                        else
                            BackgroundResult.Updated(newTheme)
                    )
                })
            finish()
        }
    }

    private fun delete() {
        setResult(Activity.RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    override fun onDestroy() {
        backgroundStates.previewSourceFile?.let { ThemeBackgroundCache.invalidate(it.path); it.delete() }
        super.onDestroy()
    }

    private fun promptDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!newCreated) {
            menu.add(R.string.delete).apply {
                icon = drawable(R.drawable.ic_menu_delete)!!.apply {
                    setTint(styledColor(android.R.attr.colorControlNormal))
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    promptDelete()
                    true
                }
            }
        }
        menu.add(R.string.save).apply {
            icon = drawable(R.drawable.ic_menu_done)!!.apply {
                setTint(styledColor(android.R.attr.colorControlNormal))
            }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                done()
                true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            cancel()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
    }
}
