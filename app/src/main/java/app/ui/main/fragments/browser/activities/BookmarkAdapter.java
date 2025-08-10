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
import java.util.List;

import app.core.AIOApp;
import app.core.bases.BaseActivity;
import app.core.engines.browser.bookmarks.AIOBookmarks;
import app.core.engines.browser.bookmarks.BookmarkModel;
import app.core.engines.caches.AIOFavicons;
import lib.device.DateTimeUtils;

public class BookmarkAdapter extends BaseAdapter {

    private final WeakReference<BaseActivity> safeBaseActivityRef;
    private final OnBookmarkItemClick onBookmarkItemClick;
    private final OnBookmarkItemLongClick onBookmarkItemLongClick;
    private int currentIndex = 0;
    private final List<BookmarkModel> displayedBookmarks = new ArrayList<>();

    public BookmarkAdapter(@Nullable BookmarksActivity bookmarkActivity,
                           @Nullable OnBookmarkItemClick onBookmarkItemClick,
                           @Nullable OnBookmarkItemLongClick onBookmarkItemLongClick) {
        this.safeBaseActivityRef = new WeakReference<>(bookmarkActivity);
        this.onBookmarkItemClick = onBookmarkItemClick;
        this.onBookmarkItemLongClick = onBookmarkItemLongClick;
        loadMoreBookmarks();
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
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        BaseActivity activity = this.safeBaseActivityRef.get();
        if (activity == null) {
            return convertView != null ? convertView : new View(parent.getContext());
        }

        ViewHolder holder;

        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(activity);
            convertView = inflater.inflate(R.layout.activity_bookmarks_1_row_1, parent, false);

            holder = new ViewHolder();
            holder.bookmarkFavicon = convertView.findViewById(R.id.bookmark_url_favicon_indicator);
            holder.bookmarkTitle = convertView.findViewById(R.id.bookmark_url_title);
            holder.bookmarkDate = convertView.findViewById(R.id.bookmark_url_date);
            holder.bookmarkUrl = convertView.findViewById(R.id.bookmark_url);

            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        BookmarkModel bookmarkModel = getItem(position);
        if (bookmarkModel != null) {
            holder.bookmarkTitle.setText(bookmarkModel.getBookmarkName());
            holder.bookmarkUrl.setText(removeWwwFromUrl(bookmarkModel.getBookmarkUrl()));

            Date visitedDate = bookmarkModel.getBookmarkCreationDate();
            String formattedDate = DateTimeUtils.formatDateWithSuffix(visitedDate);
            holder.bookmarkDate.setText(formattedDate);

            convertView.setOnClickListener(view -> {
                if (onBookmarkItemClick != null) {
                    onBookmarkItemClick.onBookmarkClick(bookmarkModel);
                }
            });

            convertView.setOnLongClickListener(view -> {
                if (onBookmarkItemLongClick != null) {
                    onBookmarkItemLongClick.onBookmarkLongClick(bookmarkModel, position, view);
                }
                return true;
            });

            executeInBackground(() -> {
                AIOFavicons aioFavicon = INSTANCE.getAIOFavicon();
                String faviconCachedPath = aioFavicon.getFavicon(bookmarkModel.getBookmarkUrl());
                if (faviconCachedPath != null && !faviconCachedPath.isEmpty()) {
                    File faviconImg = new File(faviconCachedPath);
                    if (faviconImg.exists()) {
                        executeOnMainThread(() ->
                                holder.bookmarkFavicon.setImageURI(Uri.fromFile(faviconImg)));
                    }
                }
            });
        }

        return convertView;
    }

    public void loadMoreBookmarks() {
        AIOBookmarks aioBookmarks = INSTANCE.getAIOBookmarks();
        List<BookmarkModel> fullList = aioBookmarks.getBookmarkLibrary();
        if (currentIndex >= fullList.size()) return;

        int itemsToLoad = Math.min(50, fullList.size() - currentIndex);
        int endIndex = currentIndex + itemsToLoad;

        for (int index = currentIndex; index < endIndex; index++)
            displayedBookmarks.add(fullList.get(index));

        currentIndex = endIndex;
        notifyDataSetChanged();
    }

    public void resetBookmarkAdapter() {
        currentIndex = 0;
        displayedBookmarks.clear();
        notifyDataSetChanged();
    }

    public interface OnBookmarkItemClick {
        void onBookmarkClick(@NonNull BookmarkModel bookmarkModel);
    }

    public interface OnBookmarkItemLongClick {
        void onBookmarkLongClick(@NonNull BookmarkModel bookmarkModel,
                                 int position, @NonNull View listView);
    }

    private static class ViewHolder {
        ImageView bookmarkFavicon;
        TextView bookmarkTitle;
        TextView bookmarkUrl;
        TextView bookmarkDate;
    }
}