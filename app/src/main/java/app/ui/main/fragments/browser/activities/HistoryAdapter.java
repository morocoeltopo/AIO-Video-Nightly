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

import app.core.bases.BaseActivity;
import app.core.engines.browser.history.HistoryModel;
import app.core.engines.caches.AIOFavicons;
import lib.device.DateTimeUtils;

public class HistoryAdapter extends BaseAdapter {

    private final WeakReference<BaseActivity> safeBaseActivityRef;
    private final OnHistoryItemClick onHistoryItemClick;
    private final OnHistoryItemLongClick onHistoryItemLongClick;
    private int currentIndex = 0;
    private final List<HistoryModel> displayedHistory = new ArrayList<>();

    public HistoryAdapter(@Nullable HistoryActivity historyActivity,
                          @Nullable OnHistoryItemClick onHistoryItemClick,
                          @Nullable OnHistoryItemLongClick onHistoryItemLongClick) {
        this.safeBaseActivityRef = new WeakReference<>(historyActivity);
        this.onHistoryItemClick = onHistoryItemClick;
        this.onHistoryItemLongClick = onHistoryItemLongClick;
        loadMoreHistory();
    }

    @Override
    public int getCount() {
        return displayedHistory.size();
    }

    @Nullable
    @Override
    public HistoryModel getItem(int position) {
        if (position >= 0 && position < displayedHistory.size()) {
            return displayedHistory.get(position);
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
            convertView = inflater.inflate(R.layout.activity_browser_history_1_row_1, parent, false);

            holder = new ViewHolder();
            holder.historyFavicon = convertView.findViewById(R.id.img_history_url_favicon);
            holder.historyTitle = convertView.findViewById(R.id.txt_history_url_title);
            holder.historyDate = convertView.findViewById(R.id.txt_history_url_date);
            holder.historyUrl = convertView.findViewById(R.id.txt_history_url);

            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        HistoryModel historyModel = getItem(position);
        if (historyModel != null) {
            holder.historyTitle.setText(historyModel.getHistoryTitle());
            holder.historyUrl.setText(removeWwwFromUrl(historyModel.getHistoryUrl()));

            Date visitedDate = historyModel.getHistoryVisitDateTime();
            String formattedDate = DateTimeUtils.formatDateWithSuffix(visitedDate);
            holder.historyDate.setText(formattedDate);

            convertView.setOnClickListener(view -> {
                if (onHistoryItemClick != null) {
                    onHistoryItemClick.onHistoryItemClick(historyModel);
                }
            });

            convertView.setOnLongClickListener(view -> {
                if (onHistoryItemLongClick != null) {
                    onHistoryItemLongClick.onHistoryItemLongClick(historyModel, position, view);
                }
                return true;
            });

            executeInBackground(() -> {
                AIOFavicons aioFavicon = INSTANCE.getAIOFavicon();
                String faviconCachedPath = aioFavicon.getFavicon(historyModel.getHistoryUrl());
                if (faviconCachedPath != null && !faviconCachedPath.isEmpty()) {
                    File faviconImg = new File(faviconCachedPath);
                    if (faviconImg.exists()) {
                        executeOnMainThread(() ->
                                holder.historyFavicon.setImageURI(Uri.fromFile(faviconImg)));
                    }
                }
            });
        }

        return convertView;
    }

    public void loadMoreHistory() {
        List<HistoryModel> fullList = INSTANCE.getAIOHistory().getHistoryLibrary();
        if (currentIndex >= fullList.size()) return;

        int itemsToLoad = Math.min(50, fullList.size() - currentIndex);
        int endIndex = currentIndex + itemsToLoad;

        for (int index = currentIndex; index < endIndex; index++)
            displayedHistory.add(fullList.get(index));

        currentIndex = endIndex;
        notifyDataSetChanged();
    }

    public void resetHistoryAdapter() {
        currentIndex = 0;
        displayedHistory.clear();
        notifyDataSetChanged();
    }

    public interface OnHistoryItemClick {
        void onHistoryItemClick(@NonNull HistoryModel historyModel);
    }

    public interface OnHistoryItemLongClick {
        void onHistoryItemLongClick(@NonNull HistoryModel historyModel,
                                    int position, @NonNull View listView);
    }

    private static class ViewHolder {
        ImageView historyFavicon;
        TextView historyTitle;
        TextView historyUrl;
        TextView historyDate;
    }
}