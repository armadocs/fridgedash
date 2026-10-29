package app.fridgedash;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.List;

/** Grid of all launchable apps. */
final class AppAdapter extends BaseAdapter {
    private final Context ctx;
    private List<AppEntry> apps = new ArrayList<>();

    AppAdapter(Context ctx) {
        this.ctx = ctx;
    }

    void setApps(List<AppEntry> apps) {
        this.apps = apps;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return apps.size(); }
    @Override public AppEntry getItem(int position) { return apps.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Ui.IconTile tile;
        if (convertView instanceof Ui.IconTile) {
            tile = (Ui.IconTile) convertView;
        } else {
            tile = new Ui.IconTile(ctx, 64, 16);
            tile.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT));
            // The grid handles clicks; the tile only draws.
            tile.setClickable(false);
            tile.setFocusable(false);
        }
        tile.bind(apps.get(position));
        return tile;
    }
}
