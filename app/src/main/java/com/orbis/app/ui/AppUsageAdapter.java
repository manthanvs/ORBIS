package com.orbis.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orbis.app.R;
import com.orbis.app.usage.AppUsage;
import com.orbis.app.usage.DurationFormatter;

import java.util.ArrayList;
import java.util.List;

/** Today's per-app split: name, time, and a bar for its share of the day. */
public final class AppUsageAdapter extends RecyclerView.Adapter<AppUsageAdapter.Row> {

    private final List<AppUsage> entries = new ArrayList<>();
    private long totalMillis;

    public void submit(List<AppUsage> next, long totalMillis) {
        entries.clear();
        entries.addAll(next);
        this.totalMillis = totalMillis;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_app_usage, parent, false);
        return new Row(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position) {
        AppUsage entry = entries.get(position);
        holder.name.setText(entry.getApp().getDisplayName());
        holder.duration.setText(DurationFormatter.format(entry.getDurationMillis()));
        int share =
                totalMillis <= 0L
                        ? 0
                        : (int) Math.round(100.0 * entry.getDurationMillis() / totalMillis);
        holder.share.setProgress(share);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView duration;
        final ProgressBar share;

        Row(View view) {
            super(view);
            name = view.findViewById(R.id.app_name);
            duration = view.findViewById(R.id.app_duration);
            share = view.findViewById(R.id.app_share);
        }
    }
}
