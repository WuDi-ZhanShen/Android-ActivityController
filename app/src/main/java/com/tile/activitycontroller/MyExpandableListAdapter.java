package com.tile.activitycontroller;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Expandable app/activity list shared by the "all" and "blocked" pages. */
public class MyExpandableListAdapter extends BaseExpandableListAdapter {

    private final List<MainActivity.AppInfo> appList;
    private final HashMap<MainActivity.AppInfo, List<MainActivity.ActivityItem>> activityMap;
    private final Map<String, Boolean> targetPkgNamesMap;
    private final MainActivity activity;

    public MyExpandableListAdapter(List<MainActivity.AppInfo> appList,
                                   HashMap<MainActivity.AppInfo, List<MainActivity.ActivityItem>> activityMap,
                                   Map<String, Boolean> targetPkgNamesMap,
                                   MainActivity activity) {
        this.appList = appList;
        this.activityMap = activityMap;
        this.targetPkgNamesMap = targetPkgNamesMap;
        this.activity = activity;
    }

    @Override
    public int getGroupCount() {
        return appList.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        List<MainActivity.ActivityItem> list = activityMap.get(appList.get(groupPosition));
        return list == null ? 0 : list.size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return appList.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return activityMap.get(appList.get(groupPosition)).get(childPosition);
    }

    @Override public long getGroupId(int groupPosition) { return groupPosition; }
    @Override public long getChildId(int groupPosition, int childPosition) { return childPosition; }
    @Override public boolean hasStableIds() { return false; }

    static class GroupViewHolder {
        TextView label;
        TextView name;
        TextView count;
        ImageView icon;
        ImageView indicator;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        GroupViewHolder holder;
        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            convertView = inflater.inflate(R.layout.group_item, parent, false);
            holder = new GroupViewHolder();
            holder.label = convertView.findViewById(R.id.package_label);
            holder.name = convertView.findViewById(R.id.package_name);
            holder.count = convertView.findViewById(R.id.activity_count);
            holder.icon = convertView.findViewById(R.id.package_icon);
            holder.indicator = convertView.findViewById(R.id.group_indicator);
            convertView.setTag(holder);
        } else {
            holder = (GroupViewHolder) convertView.getTag();
        }

        MainActivity.AppInfo app = (MainActivity.AppInfo) getGroup(groupPosition);
        holder.label.setText(app.packageLabel);
        holder.name.setText(app.packageName);
        holder.icon.setImageDrawable(app.packageIcon);
        holder.count.setText(getChildrenCount(groupPosition) + " 个");

        float targetRotation = isExpanded ? 90f : 0f;
        holder.indicator.animate().cancel();
        if (holder.indicator.isLaidOut()
                && Math.abs(holder.indicator.getRotation() - targetRotation) > 1f) {
            holder.indicator.animate()
                    .rotation(targetRotation)
                    .setDuration(180)
                    .start();
        } else {
            holder.indicator.setRotation(targetRotation);
        }
        holder.indicator.setContentDescription(isExpanded ? "展开状态" : "收起状态");
        return convertView;
    }

    static class ChildViewHolder {
        TextView label;
        TextView name;
        ImageView icon;
        Switch aSwitch;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                             View convertView, ViewGroup parent) {
        ChildViewHolder holder;
        MainActivity.ActivityItem activityItem =
                (MainActivity.ActivityItem) getChild(groupPosition, childPosition);
        ComponentName componentName = new ComponentName(
                activityItem.activityInfo.packageName, activityItem.activityInfo.name);
        String shortString = componentName.flattenToShortString();

        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            convertView = inflater.inflate(R.layout.child_item, parent, false);
            holder = new ChildViewHolder();
            holder.label = convertView.findViewById(R.id.activity_label);
            holder.name = convertView.findViewById(R.id.activity_name);
            holder.icon = convertView.findViewById(R.id.activity_icon);
            holder.aSwitch = convertView.findViewById(R.id.item_switch);
            convertView.setTag(holder);
        } else {
            holder = (ChildViewHolder) convertView.getTag();
        }

        // UI semantics are intentionally the inverse of the old version:
        // ON = allowed, OFF = blocked. A blocked Activity therefore looks visibly "off".
        holder.aSwitch.setOnCheckedChangeListener(null);
        holder.aSwitch.setChecked(!targetPkgNamesMap.containsKey(shortString));
        holder.aSwitch.setOnCheckedChangeListener((button, allowed) -> {
            activity.setActivityAllowed(shortString, allowed);
            Toast.makeText(parent.getContext(),
                    allowed ? "已允许此 Activity 启动" : "已禁止此 Activity 启动",
                    Toast.LENGTH_SHORT).show();
        });

        holder.label.setText(activityItem.activityLabel);
        holder.name.setText(activityItem.activityInfo.name);
        holder.icon.setImageDrawable(activityItem.activityIcon);

        MainActivity.AppInfo appInfo = appList.get(groupPosition);
        if (appInfo.launcherActivityNames.contains(activityItem.activityInfo.name)) {
            holder.name.setTextColor(activity.getColor(R.color.launcher));
        } else {
            holder.name.setTextColor(activity.getColor(R.color.text_secondary));
        }

        final ChildViewHolder clickHolder = holder;
        convertView.setOnClickListener(view -> clickHolder.aSwitch.performClick());
        convertView.setOnLongClickListener(view -> {
            Intent intent = new Intent().setComponent(componentName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (activityItem.activityInfo.exported) {
                try {
                    parent.getContext().startActivity(intent);
                    Toast.makeText(parent.getContext(), "尝试启动此 Activity", Toast.LENGTH_SHORT).show();
                } catch (Throwable tr) {
                    Toast.makeText(parent.getContext(), "启动失败：" + tr.getClass().getSimpleName(),
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                IUserService service = activity.getUserService();
                if (service == null) {
                    Toast.makeText(parent.getContext(), "请先启动服务", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        service.launchUnexportedActivity(intent);
                        Toast.makeText(parent.getContext(), "尝试启动此 Activity", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(parent.getContext(), "请以 root 权限启动服务",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
            return true;
        });

        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }
}
