package com.bernaferrari.sdkmonitor.main

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import com.airbnb.epoxy.EpoxyRecyclerView
import com.airbnb.mvrx.*
import com.bernaferrari.sdkmonitor.R
import com.bernaferrari.sdkmonitor.core.MvRxEpoxyController
import com.bernaferrari.sdkmonitor.core.simpleController
import com.bernaferrari.sdkmonitor.data.App
import com.bernaferrari.sdkmonitor.emptyContent
import com.bernaferrari.sdkmonitor.extensions.darken
import com.bernaferrari.sdkmonitor.extensions.inflate
import com.bernaferrari.sdkmonitor.extensions.onTextChanged
import com.bernaferrari.sdkmonitor.extensions.toDpF
import com.bernaferrari.sdkmonitor.util.InsetDecoration
import com.bernaferrari.sdkmonitor.util.hideKeyboard
import com.bernaferrari.sdkmonitor.util.hideKeyboardWhenNecessary
import com.bernaferrari.sdkmonitor.views.MainRowModel_
import com.bernaferrari.sdkmonitor.views.loadingRow
import com.bernaferrari.sdkmonitor.views.mainRow
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import kotlinx.android.synthetic.main.main_fragment.*
import kotlinx.coroutines.experimental.*
import kotlin.coroutines.experimental.CoroutineContext


class AppVersion(
    val app: App,
    val sdkVersion: Int,
    val lastUpdateTime: String
)

data class AppDetails(val title: String, val subtitle: String)

data class MainState(val listOfItems: Async<List<AppVersion>> = Loading()) : MvRxState

class MainFragment : BaseMvRxFragment(), CoroutineScope {

    private val viewModel: MainRxViewModel by fragmentViewModel()

    override val coroutineContext: CoroutineContext = Dispatchers.Main + Job()

    private val standardItemDecorator by lazy {
        InsetDecoration(
            resources.getDimensionPixelSize(R.dimen.right_padding_for_fast_scroller),
            false,
            true
        )
    }

    private val inputMethodManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity?.getSystemService<InputMethodManager>()
        } else {
            activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        } ?: throw Exception("null activity. Can't bind inputMethodManager")
    }

    private val showDialog: ((App) -> (Unit)) = {
        val customView = parentLayout.inflate(R.layout.details_fragment)

        val bottomDialog = BottomSheetDialog(requireContext()).also { btn ->
            btn.setContentView(customView)
            btn.show()
        }

        customView.findViewById<ImageView>(R.id.closecontent).setOnClickListener { _ ->
            bottomDialog.dismiss()
        }

        customView.findViewById<TextView>(R.id.titlecontent).text = it.title

        customView.findViewById<EpoxyRecyclerView>(R.id.recycler).also { epoxyRecycler ->

            val detailsController = DetailsController()
            epoxyRecycler?.setController(detailsController)

            runBlocking {
                val packageName = it.packageName
                val data = viewModel.fetchAppDetails(packageName)
                val versions = viewModel.fetchAllVersions(packageName)
                detailsController.setData(data, versions)
            }
        }
    }

    val epoxyController by lazy { epoxyController() }

    fun epoxyController(): MvRxEpoxyController = simpleController(viewModel) { state ->

        when (state.listOfItems) {
            is Loading ->
                loadingRow { id("loading") }
            is Fail ->
                emptyContent {
                    this.id("error")
                    this.label(state.listOfItems.error.localizedMessage)
                }
            is Success -> {
                queryInput.hint = "Search ${viewModel.maxListSize} apps.."

                if (state.listOfItems()?.isEmpty() == true) {
                    emptyContent {
                        this.id("empty result")
                        this.label(getString(R.string.empty_search))
                    }
                }
            }
        }

        val cornerRadius = 8.toDpF(resources)

        state.listOfItems()?.forEach {
            mainRow {
                id(it.app.packageName)

                this.app(it)

                val topShape = createShape(it.app.backgroundColor, false, cornerRadius)
                val bottomShape = createShape(it.app.backgroundColor.darken, true, cornerRadius)

                this.bottomShape(bottomShape)
                this.topShape(topShape)

                this.clickListener { _ -> showDialog.invoke(it.app) }
            }
        }

    }

    private fun createShape(color: Int, isBottom: Boolean, cornerRadius: Float): Drawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadii = if (isBottom) {
            floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        } else {
            floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f, 0f, 0f)
        }
        shape.setColor(color)
        return shape
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.main_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.itemsList.isEmpty()) {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        recycler.setController(epoxyController)
        recycler.addItemDecoration(standardItemDecorator)
        setupFastScroller(recycler.layoutManager as? LinearLayoutManager)

        setupDataAndSearch()

        viewModel.updateAll()

        hideKeyboardWhenNecessary(
            requireActivity(),
            inputMethodManager,
            recycler,
            queryInput
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        epoxyController.onRestoreInstanceState(savedInstanceState)
    }

    override fun invalidate() {
        recycler.requestModelBuild()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        epoxyController.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        epoxyController.cancelPendingModelBuild()
        super.onDestroyView()
    }

    private fun setupDataAndSearch() {

        var work: Job? = null

        viewModel.relay.accept(queryInput.text.toString())

        queryClear.setOnClickListener { queryInput.setText("") }

        queryInput.onTextChanged {
            queryClear.isVisible = it.isNotEmpty()
            work?.cancel()
            work = launch {
                // If the user types anything before data has loaded, this will
                // delay and try again until it is available or the user types
                // another thing.
                // Without this, the input would be ignored while data is loading.
                while (!viewModel.hasLoaded) delay(200)
                viewModel.relay.accept(it.toString())
            }
        }
    }

    override fun onDestroy() {
        coroutineContext.cancel()
        super.onDestroy()
    }

    private fun setupFastScroller(llm: LinearLayoutManager?) {
        val linearLayoutManager = llm ?: return

        fastscroller.setupWithRecyclerView(
            recyclerView = recycler,
            useDefaultScroller = false,
            getItemIndicator = {

                if (epoxyController.adapter.getModelAtPosition(it) !is MainRowModel_) {
                    return@setupWithRecyclerView null
                }

                // it might be null when model is updated really fast
                val itemFromList = viewModel.itemsList.getOrNull(it)
                        ?: return@setupWithRecyclerView null

                val letter = itemFromList.app.title.substring(0, 1)
                val index = if (letter[0].isDigit()) "#" else letter.toUpperCase()

                FastScrollItemIndicator.Text(index) // Return a text indicator
            }
        )

        val smoothScroller: LinearSmoothScroller = object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }

        fastscroller.itemIndicatorSelectedCallbacks += object :
            FastScrollerView.ItemIndicatorSelectedCallback {
            override fun onItemIndicatorSelected(
                indicator: FastScrollItemIndicator,
                indicatorCenterY: Int,
                itemPosition: Int
            ) {
                recycler.stopScroll()
                inputMethodManager.hideKeyboard(queryInput)
                smoothScroller.targetPosition = itemPosition
                linearLayoutManager.startSmoothScroll(smoothScroller)
            }
        }

        fastscroller_thumb.setupWithFastScroller(fastscroller)
    }
}
