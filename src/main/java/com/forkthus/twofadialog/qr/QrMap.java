package com.forkthus.twofadialog.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import org.bukkit.Bukkit;
//import org.bukkit.Color;
import java.awt.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import static org.bukkit.Material.FILLED_MAP;

public final class QrMap {
    public static final NamespacedKey QR_TAG = new NamespacedKey("twf", "qrmap");

    public static ItemStack make(Player p, String text) {
        World w = p.getWorld();
        MapView view = Bukkit.createMap(w);
        view.getRenderers().forEach(view::removeRenderer);
        view.addRenderer(new MapRenderer(false) {
            private boolean drawn = false;
            @Override public void render(MapView map, MapCanvas canvas, Player player) {
                if (drawn) return;
                drawn = true;
                try {
                    var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 120, 120);
                    int ox = (128 - matrix.getWidth()) / 2, oy = (128 - matrix.getHeight()) / 2;
                    for (int y = 0; y < matrix.getHeight(); y++) {
                        for (int x = 0; x < matrix.getWidth(); x++) {
                            canvas.setPixelColor(ox + x, oy + y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        });

        ItemStack mapItem = new ItemStack(FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        meta.displayName(net.kyori.adventure.text.Component.text("Scan to set up 2FA"));
        meta.getPersistentDataContainer().set(QR_TAG, PersistentDataType.BYTE, (byte)1);
        mapItem.setItemMeta(meta);
        return mapItem;
    }
}
