package net.wigle.wigleandroid.warstar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Device detail editor for multiple independent icon and color annotations. */
public final class TagDialog {
    private TagDialog() {}

    public static void show(Context context, String type, String address) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);

        EditText label = new EditText(context);
        label.setSingleLine(true);
        label.setHint("Tag label");
        content.addView(label);

        Spinner icon = new Spinner(context);
        icon.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, DeviceTags.ICONS));
        content.addView(icon);

        String[] colors = {"Red", "Orange", "Gold", "Green", "Blue", "Purple", "Pink", "Slate"};
        Spinner color = new Spinner(context);
        color.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, colors));
        content.addView(color);

        CheckBox alert = new CheckBox(context);
        alert.setText("Alert when seen (Wi-Fi or Bluetooth)");
        content.addView(alert);

        EditText target = new EditText(context);
        target.setSingleLine(true);
        target.setHint("StarIntel target document ID (optional)");
        content.addView(target);

        TextView existing = new TextView(context);
        existing.setText("Tags  •  tap to remove");
        content.addView(existing);
        ChipGroup chips = new ChipGroup(context);
        content.addView(chips);
        JSONArray tags = DeviceTags.forDevice(context, type, address);
        for (int i = 0; i < tags.length(); i++) {
            JSONObject entry = tags.optJSONObject(i);
            if (entry == null) continue;
            final int index = i;
            Chip chip = new Chip(context);
            chip.setText(entry.optString("icon") + " " + entry.optString("label"));
            chip.setChipBackgroundColor(ColorStateList.valueOf(entry.optInt("color", DeviceTags.COLORS[0])));
            chip.setTextColor(Color.WHITE);
            chip.setOnClickListener(view -> new AlertDialog.Builder(context)
                    .setMessage("Remove this tag?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        try {
                            DeviceTags.remove(context, type, address, index);
                            Toast.makeText(context, "Tag removed", Toast.LENGTH_SHORT).show();
                        } catch (JSONException exception) {
                            Toast.makeText(context, "Could not remove tag", Toast.LENGTH_SHORT).show();
                        }
                    }).show());
            chips.addView(chip);
        }

        new AlertDialog.Builder(context)
                .setTitle(type + " • " + address)
                .setView(content)
                .setNegativeButton("Done", null)
                .setPositiveButton("Add tag", (dialog, which) -> {
                    try {
                        DeviceTags.add(context, type, address, label.getText().toString(),
                                DeviceTags.ICONS[icon.getSelectedItemPosition()],
                                DeviceTags.COLORS[color.getSelectedItemPosition()],
                                alert.isChecked(), target.getText().toString());
                        Toast.makeText(context, "Tag saved", Toast.LENGTH_SHORT).show();
                    } catch (JSONException | IllegalArgumentException exception) {
                        Toast.makeText(context, exception.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }
}
