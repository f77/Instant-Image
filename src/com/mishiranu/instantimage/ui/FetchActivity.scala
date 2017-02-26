package com.mishiranu.instantimage.ui

import android.app.{Activity, ActivityOptions}
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.{Bundle, Parcelable}
import android.view.{Menu, MenuItem, View, ViewGroup, ViewTreeObserver}
import android.widget.{AdapterView, GridView, SearchView, Toast}

import com.mishiranu.instantimage.R
import com.mishiranu.instantimage.model.Image
import com.mishiranu.instantimage.util.Preferences
import com.mishiranu.instantimage.util.ScalaHelpers._

import scala.collection.mutable

class FetchActivity extends Activity with ViewTreeObserver.OnGlobalLayoutListener with SearchView.OnQueryTextListener
  with MenuItem.OnActionExpandListener with LoadingDialog.Callback with AdapterView.OnItemClickListener {
  import FetchActivity._

  private val images = new mutable.ArrayBuffer[Image]
  private val selectedImages = new mutable.LinkedHashSet[Image]
  private val gridAdapter = new GridAdapter()

  private var searchView: SearchView = _
  private var gridView: GridView = _

  private var keepSearchFocus: Boolean = false

  protected override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    getActionBar.setDisplayHomeAsUpEnabled(true)

    searchView = new SearchView(getActionBar.getThemedContext)
    searchView.setOnQueryTextListener(this)

    keepSearchFocus = savedInstanceState == null

    gridView = new GridView(this)
    gridView.setId(android.R.id.list)
    gridView.setOnItemClickListener(this)
    gridView.setAdapter(gridAdapter)
    val density = getResources.getDisplayMetrics.density
    val spacing = (GRID_SPACING_DP * density + 0.5f).toInt
    gridView.setHorizontalSpacing(spacing)
    gridView.setVerticalSpacing(spacing)
    gridView.setPadding(spacing, spacing, spacing, spacing)
    gridView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY)
    gridView.setClipToPadding(false)
    gridView.getViewTreeObserver.addOnGlobalLayoutListener(this)
    registerForContextMenu(gridView)
    addContentView(gridView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT))

    val loadingDialog = getFragmentManager.findFragmentByTag(LoadingDialog.TAG).asInstanceOf[LoadingDialog]
    if (loadingDialog != null) {
      loadingDialog.setCallback(this)
    }
    if (savedInstanceState != null) {
      val images = savedInstanceState.getParcelableArrayList[Image](EXTRA_IMAGES)
      if (images != null) {
        import scala.collection.JavaConversions._
        this.images ++= images
        gridAdapter.setImages(this.images)
      }
      val selectedImages = savedInstanceState.getParcelableArrayList[Image](EXTRA_SELECTED_IMAGES)
      if (selectedImages != null) {
        import scala.collection.JavaConversions._
        this.selectedImages ++= selectedImages
      }
    }
  }

  override protected def onSaveInstanceState(outState: Bundle): Unit = {
    super.onSaveInstanceState(outState)
    import scala.collection.JavaConverters._
    outState.putParcelableArrayList(EXTRA_IMAGES, new java.util.ArrayList(images.asJava))
    outState.putParcelableArrayList(EXTRA_SELECTED_IMAGES, new java.util.ArrayList(selectedImages.asJava))
  }

  private var lastGridViewWidth = -1

  private val layoutRunnable: Runnable = () => {
    val position = gridView.getFirstVisiblePosition
    val density = getResources.getDisplayMetrics.density
    val sizeDp = if (getResources.getConfiguration.smallestScreenWidthDp >= 600) 200 else 150
    val size = (sizeDp * density + 0.5f).toInt
    val spacing = gridView.getHorizontalSpacing
    val numColumns = (lastGridViewWidth - spacing) / (size + spacing)
    gridView.setNumColumns(numColumns)
    gridView.setSelection(position)
  }

  override def onGlobalLayout(): Unit = {
    val width = gridView.getWidth
    if (lastGridViewWidth != width) {
      lastGridViewWidth = width
      gridView.removeCallbacks(layoutRunnable)
      gridView.post(layoutRunnable)
    }
  }

  override def onQueryTextChange(newText: String): Boolean = false

  override def onQueryTextSubmit(query: String): Boolean = {
    searchView.clearFocus()
    if (query.length() > 0) {
      new LoadingDialog(query, null, this).show(getFragmentManager, LoadingDialog.TAG)
    }
    false
  }

  override def onMenuItemActionExpand(item: MenuItem): Boolean = true

  override def onMenuItemActionCollapse(item: MenuItem): Boolean = {
    finish()
    false
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    // noinspection ScalaDeprecation
    def getDrawable(resId: Int): Drawable = sdk(21) {
      case true => FetchActivity.this.getDrawable(resId)
      case false => getResources.getDrawable(resId)
    }.get

    menu.add(0, OPTIONS_ITEM_SEARCH, 0, "").setActionView(searchView)
      .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
      .setOnActionExpandListener(this).expandActionView()
    menu.add(0, OPTIONS_ITEM_CROP, 0, R.string.text_crop_thumbnails).setIcon(getDrawable(sdk(21) {
      case true => R.drawable.ic_crop_white
      case false => R.drawable.ic_action_crop
    }.get).mutate).setCheckable(true).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    if (keepSearchFocus) {
      keepSearchFocus = false
    } else {
      searchView.clearFocus()
    }
    true
  }

  override def onPrepareOptionsMenu(menu: Menu): Boolean = {
    val cropMenuItem = menu.findItem(OPTIONS_ITEM_CROP)
    cropMenuItem.setChecked(Preferences.cropThumbnails)
    cropMenuItem.getIcon.setAlpha(if (Preferences.cropThumbnails) 0xff else 0x7f)
    super.onPrepareOptionsMenu(menu)
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home =>
        finish()
        true
      case OPTIONS_ITEM_CROP =>
        Preferences.setCropThumbnails(!item.isChecked)
        gridAdapter.notifyDataSetChanged()
        invalidateOptionsMenu()
        true
      case _ =>
        super.onOptionsItemSelected(item)
    }
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent): Unit = {
    requestCode match {
      case REQUEST_CODE_PREVIEW =>
        if (resultCode == Activity.RESULT_OK) {
          val image = data.getParcelableExtra[Image](PreviewActivity.EXTRA_IMAGE)
          selectedImages += image
          new LoadingDialog(null, selectedImages.map(_.imageUriString).head, this)
            .show(getFragmentManager, LoadingDialog.TAG)
        }
      case _ =>
        super.onActivityResult(requestCode, resultCode, data)
    }
  }

  override def onQueryLoadingSuccess(images: List[Image]): Unit = {
    this.images.clear()
    this.selectedImages.clear()
    this.images ++= images
    gridAdapter.setImages(images)
    gridView.post(() => gridView.setSelection(0))
  }

  override def onQueryLoadingError(errorMessageId: Int): Unit = {
    Toast.makeText(this, getString(errorMessageId), Toast.LENGTH_SHORT).show()
  }

  override def onImageLoadingSuccess(contentId: Int, mimeType: String): Unit = {
    val uri = ImageProvider.buildUri(contentId)
    setResult(Activity.RESULT_OK, new Intent().setDataAndType(uri, mimeType))
    finish()
  }

  override def onImageLoadingError(errorMessageId: Int): Unit = {
    onQueryLoadingError(errorMessageId)
  }

  override def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long): Unit = {
    val intent = new Intent(this, classOf[PreviewActivity])
      .putExtra(PreviewActivity.EXTRA_IMAGE, gridAdapter.getItem(position).asInstanceOf[Parcelable])
    sdk(21) {
      case true =>
        val view = parent.getChildAt(position - parent.getFirstVisiblePosition)
        val (imageView, transition) = gridAdapter.extractImageViewForTransition(view)
        intent.putExtra(PreviewActivity.EXTRA_TRANSITION, transition)
        startActivityForResult(intent, REQUEST_CODE_PREVIEW,
          ActivityOptions.makeSceneTransitionAnimation(this, imageView -> transition).toBundle)
      case false =>
        startActivityForResult(intent, REQUEST_CODE_PREVIEW)
    }
  }
}

object FetchActivity {
  private val EXTRA_IMAGES = "images"
  private val EXTRA_SELECTED_IMAGES = "selected_images"

  private val REQUEST_CODE_PREVIEW = 1

  private val OPTIONS_ITEM_SEARCH = 1
  private val OPTIONS_ITEM_CROP = 2

  private val GRID_SPACING_DP = 4
}
