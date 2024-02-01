package su.sres.securesms.keyboard.gif

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import su.sres.securesms.LoggingFragment
import su.sres.securesms.R
import su.sres.securesms.conversation.ConversationActivity
import su.sres.securesms.giph.mp4.GiphyMp4Fragment
import su.sres.securesms.giph.mp4.GiphyMp4SaveResult
import su.sres.securesms.giph.mp4.GiphyMp4ViewModel
import su.sres.securesms.keyboard.emoji.KeyboardPageSearchView
import su.sres.securesms.keyboard.findListener
import su.sres.securesms.mms.AttachmentManager
import su.sres.securesms.util.views.SimpleProgressDialog

class GifKeyboardPageFragment : LoggingFragment(R.layout.gif_keyboard_page_fragment) {

  private lateinit var host: Host
  private lateinit var quickSearchAdapter: GifQuickSearchAdapter
  private lateinit var giphyMp4ViewModel: GiphyMp4ViewModel

  private lateinit var viewModel: GifKeyboardPageViewModel

  private var progressDialog: AlertDialog? = null
  private lateinit var quickSearchList: RecyclerView

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    host = findListener<Host>() ?: throw AssertionError("Parent fragment or activity must implement Host")

    childFragmentManager.beginTransaction()
      .replace(R.id.gif_keyboard_giphy_frame, GiphyMp4Fragment.create(host.isMms()))
      .commitAllowingStateLoss()

    val searchKeyboard: KeyboardPageSearchView = view.findViewById(R.id.gif_keyboard_search_text)
    searchKeyboard.callbacks = object : KeyboardPageSearchView.Callbacks {
      override fun onClicked() {
        openGifSearch()
      }
    }

    view.findViewById<View>(R.id.gif_keyboard_search).setOnClickListener { openGifSearch() }

    quickSearchList = view.findViewById(R.id.gif_keyboard_quick_search_recycler)
    quickSearchAdapter = GifQuickSearchAdapter(this::onQuickSearchSelected)
    quickSearchList.adapter = quickSearchAdapter

    giphyMp4ViewModel = ViewModelProviders.of(requireActivity(), GiphyMp4ViewModel.Factory(host.isMms())).get(GiphyMp4ViewModel::class.java)
    giphyMp4ViewModel.saveResultEvents.observe(viewLifecycleOwner, this::handleGiphyMp4SaveResult)
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    viewModel = ViewModelProviders.of(requireActivity()).get(GifKeyboardPageViewModel::class.java)
    updateQuickSearchTabs()
  }

  private fun onQuickSearchSelected(gifQuickSearchOption: GifQuickSearchOption) {
    if (viewModel.selectedTab == gifQuickSearchOption) {
      return
    }

    viewModel.selectedTab = gifQuickSearchOption
    giphyMp4ViewModel.updateSearchQuery(gifQuickSearchOption.query)

    updateQuickSearchTabs()
  }

  private fun updateQuickSearchTabs() {
    val quickSearches: List<GifQuickSearch> = GifQuickSearchOption.ranked
      .map { search -> GifQuickSearch(search, search == viewModel.selectedTab) }

    quickSearchAdapter.submitList(quickSearches, this::scrollToTab)
  }

  private fun scrollToTab() {
    quickSearchList.post { quickSearchList.smoothScrollToPosition(GifQuickSearchOption.ranked.indexOf(viewModel.selectedTab)) }
  }

  private fun handleGiphyMp4SaveResult(result: GiphyMp4SaveResult) {
    if (result is GiphyMp4SaveResult.Success) {
      hideProgressDialog()
      handleGiphyMp4SuccessfulResult(result)
    } else if (result is GiphyMp4SaveResult.Error) {
      hideProgressDialog()
      handleGiphyMp4ErrorResult()
    } else {
      progressDialog = SimpleProgressDialog.show(requireContext())
    }
  }

  private fun hideProgressDialog() {
    progressDialog?.dismiss()
  }

  private fun handleGiphyMp4SuccessfulResult(success: GiphyMp4SaveResult.Success) {
    host.onGifSelectSuccess(success.blobUri, success.width, success.height)
  }

  private fun handleGiphyMp4ErrorResult() {
    Toast.makeText(requireContext(), R.string.GiphyActivity_error_while_retrieving_full_resolution_gif, Toast.LENGTH_LONG).show()
  }

  private fun openGifSearch() {
  //  AttachmentManager.selectGif(requireActivity(), ConversationActivity.PICK_GIF, host.isMms())
  }

  interface Host {
    fun isMms(): Boolean
    fun onGifSelectSuccess(blobUri: Uri, width: Int, height: Int)
  }
}