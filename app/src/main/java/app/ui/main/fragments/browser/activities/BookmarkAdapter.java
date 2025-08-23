package app.ui.main.fragments.browser.activities;

import static app.core.AIOApp.INSTANCE;
import static lib.networks.URLUtilityKT.removeWwwFromUrl;
import static lib.process.AsyncJobUtils.executeInBackground;
import static lib.process.AsyncJobUtils.executeOnMainThread;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aio.R;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;

import app.core.bases.BaseActivity;
import app.core.engines.browser.bookmarks.AIOBookmarks;
import app.core.engines.browser.bookmarks.BookmarkModel;
import app.core.engines.caches.AIOFavicons;
import lib.device.DateTimeUtils;
import lib.process.LogHelperUtils;

/**
 * Adapter for displaying bookmark items in a list view.
 * Responsibilities:
 * - Binds {@link BookmarkModel} data (title, URL, favicon, creation date) to list rows.
 * - Handles item click and long-click events via callback interfaces.
 * - Supports lazy-loading of bookmarks in chunks for performance.
 */
public class BookmarkAdapter extends BaseAdapter {

	private final LogHelperUtils logger = LogHelperUtils.from(getClass());

	private final WeakReference<BaseActivity> safeBaseActivityRef;
	private final OnBookmarkItemClick onBookmarkItemClick;
	private final OnBookmarkItemLongClick onBookmarkItemLongClick;
	private int currentIndex = 0;
	private final ArrayList<BookmarkModel> displayedBookmarks = new ArrayList<>();

	/**
	 * Constructs a new {@link BookmarkAdapter}.
	 *
	 * @param bookmarkActivity        optional activity reference for inflating views.
	 * @param onBookmarkItemClick     callback for click events.
	 * @param onBookmarkItemLongClick callback for long-click events.
	 */
	public BookmarkAdapter(@Nullable BookmarksActivity bookmarkActivity,
						   @Nullable OnBookmarkItemClick onBookmarkItemClick,
						   @Nullable OnBookmarkItemLongClick onBookmarkItemLongClick) {
		this.safeBaseActivityRef = new WeakReference<>(bookmarkActivity);
		this.onBookmarkItemClick = onBookmarkItemClick;
		this.onBookmarkItemLongClick = onBookmarkItemLongClick;
		logger.d("BookmarkAdapter initialized. Loading initial bookmarks...");
		loadMoreBookmarks(null);
	}

	@Override
	public int getCount() {
		return displayedBookmarks.size();
	}

	@Nullable
	@Override
	public BookmarkModel getItem(int position) {
		if (position >= 0 && position < displayedBookmarks.size()) {
			return displayedBookmarks.get(position);
		}
		logger.d("Invalid position requested in getItem: " + position);
		return null;
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	/**
	 * Inflates or reuses a list row view and binds bookmark data into it.
	 *
	 * @param position    position of the item.
	 * @param convertView recycled view, if available.
	 * @param parent      parent view group.
	 * @return a populated row view.
	 */
	@Override
	public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
		BaseActivity activity = this.safeBaseActivityRef.get();
		if (activity == null) {
			// If activity reference is lost, safely return an existing or empty view
			logger.d("Activity reference lost. Returning fallback view.");
			return convertView != null ? convertView : new View(parent.getContext());
		}

		ViewHolder holder;

		// Reuse convertView if available, else inflate a new one
		if (convertView == null) {
			LayoutInflater inflater = LayoutInflater.from(activity);
			convertView = inflater.inflate(R.layout.activity_bookmarks_1_row_1, parent, false);

			// Create a new ViewHolder to cache subview references
			holder = new ViewHolder();
			holder.bookmarkFavicon = convertView.findViewById(R.id.bookmark_url_favicon_indicator);
			holder.bookmarkTitle = convertView.findViewById(R.id.bookmark_url_title);
			holder.bookmarkDate = convertView.findViewById(R.id.bookmark_url_date);
			holder.bookmarkUrl = convertView.findViewById(R.id.bookmark_url);

			// Store the holder inside the view’s tag for reuse
			convertView.setTag(holder);
			logger.d("Created new ViewHolder for position " + position);
		} else {
			// Retrieve cached ViewHolder to avoid redundant findViewById calls
			holder = (ViewHolder) convertView.getTag();
		}

		// Get current bookmark for this list position
		BookmarkModel bookmarkModel = getItem(position);
		if (bookmarkModel != null) {
			logger.d("Binding bookmark: " + bookmarkModel.getBookmarkName());

			// Bind bookmark title and URL (stripping "www")
			holder.bookmarkTitle.setText(bookmarkModel.getBookmarkName());
			holder.bookmarkUrl.setText(removeWwwFromUrl(bookmarkModel.getBookmarkUrl()));

			// Format and bind bookmark creation/visited date
			Date visitedDate = bookmarkModel.getBookmarkCreationDate();
			String formattedDate = DateTimeUtils.formatDateWithSuffix(visitedDate);
			holder.bookmarkDate.setText(formattedDate);

			// Handle single-click: open bookmark
			convertView.setOnClickListener(view -> {
				logger.d("Bookmark clicked: " + bookmarkModel.getBookmarkUrl());
				if (onBookmarkItemClick != null) {
					onBookmarkItemClick.onBookmarkClick(bookmarkModel);
				}
			});

			// Handle long-click: show bookmark options
			convertView.setOnLongClickListener(view -> {
				logger.d("Bookmark long-clicked: " + bookmarkModel.getBookmarkUrl());
				if (onBookmarkItemLongClick != null) {
					onBookmarkItemLongClick.onBookmarkLongClick(bookmarkModel, position, view);
				}
				return true; // consume event
			});

			// Load favicon asynchronously (background thread)
			executeInBackground(() -> {
				AIOFavicons aioFavicon = INSTANCE.getAIOFavicon();
				String faviconCachedPath = aioFavicon.getFavicon(bookmarkModel.getBookmarkUrl());

				// If favicon exists on disk, update ImageView on main thread
				if (faviconCachedPath != null && !faviconCachedPath.isEmpty()) {
					File faviconImg = new File(faviconCachedPath);
					if (faviconImg.exists()) {
						executeOnMainThread(() -> {
							holder.bookmarkFavicon.setImageURI(Uri.fromFile(faviconImg));
							logger.d("Favicon loaded for: " + bookmarkModel.getBookmarkUrl());
						});
					}
				}
			});
		}

		// Return the prepared/reused view
		return convertView;
	}

	/**
	 * Loads the next batch of bookmarks (400 items at a time) into the adapter.
	 */
	public void loadMoreBookmarks(@Nullable String searchTerms) {
		AIOBookmarks aioBookmarks = INSTANCE.getAIOBookmarks();
		ArrayList<BookmarkModel> fullList;
		if (searchTerms != null && !searchTerms.isEmpty()) {
			fullList = new ArrayList<>(aioBookmarks.searchBookmarksFuzzy(searchTerms));
		} else fullList = aioBookmarks.getBookmarkLibrary();

		if (currentIndex >= fullList.size()) {
			logger.d("No more bookmarks to load.");
			return;
		}

		int itemsToLoad = Math.min(400, fullList.size() - currentIndex);
		int endIndex = currentIndex + itemsToLoad;

		for (int index = currentIndex; index < endIndex; index++) {
			try {
				displayedBookmarks.add(fullList.get(index));
			} catch (Exception error) {
				error.printStackTrace();
				INSTANCE.getAIOBookmarks().readObjectFromStorage(true);
			}
		}

		currentIndex = endIndex;
		logger.d("Loaded " + itemsToLoad + " bookmarks. Current index: " + currentIndex);
		notifyDataSetChanged();
	}

	/**
	 * Resets the adapter state by clearing bookmarks and resetting index.
	 */
	public void resetBookmarkAdapter() {
		logger.d("Resetting BookmarkAdapter. Clearing " + displayedBookmarks.size() + " items.");
		currentIndex = 0;
		displayedBookmarks.clear();
		notifyDataSetChanged();
	}

	/**
	 * Callback interface for bookmark item clicks.
	 */
	public interface OnBookmarkItemClick {
		void onBookmarkClick(@NonNull BookmarkModel bookmarkModel);
	}

	/**
	 * Callback interface for bookmark item long-clicks.
	 */
	public interface OnBookmarkItemLongClick {
		void onBookmarkLongClick(@NonNull BookmarkModel bookmarkModel,
								 int position, @NonNull View listView);
	}


	/**
	 * ViewHolder pattern for caching row views.
	 */
	private static class ViewHolder {
		ImageView bookmarkFavicon;
		TextView bookmarkTitle;
		TextView bookmarkUrl;
		TextView bookmarkDate;
	}
}
