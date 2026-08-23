package com.tile.activitycontroller;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.Handler;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class tileService extends TileService {
    SharedPreferences sp;
    SharedPreferences.Editor c;

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        if (sp == null) sp = getSharedPreferences("sp", 0);

        tile.setLabel(null);
        tile.setState(true ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
        super.onStartListening();
    }

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile == null || tile.getState() == Tile.STATE_UNAVAILABLE) return;
        new Handler().post(() -> {

            tile.setState(sp.getInt("State", 99) == 99 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
            tile.updateTile();
        });

        super.onClick();
    }


}
