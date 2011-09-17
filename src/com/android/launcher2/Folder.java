/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher.R;
import com.android.launcher2.FolderInfo.FolderListener;

import java.util.ArrayList;

/**
 * Represents a set of icons chosen by the user or generated by the system.
 */
public class Folder extends LinearLayout implements DragSource, View.OnClickListener,
        View.OnLongClickListener, DropTarget, FolderListener, TextView.OnEditorActionListener {

    private static final String TAG = "Launcher.Folder";

    protected DragController mDragController;
    protected Launcher mLauncher;
    protected FolderInfo mInfo;

    static final int STATE_NONE = -1;
    static final int STATE_SMALL = 0;
    static final int STATE_ANIMATING = 1;
    static final int STATE_OPEN = 2;

    private int mExpandDuration;
    protected CellLayout mContent;
    private final LayoutInflater mInflater;
    private final IconCache mIconCache;
    private int mState = STATE_NONE;
    private static final int FULL_GROW = 0;
    private static final int PARTIAL_GROW = 1;
    private static final int REORDER_ANIMATION_DURATION = 230;
    private static final int ON_EXIT_CLOSE_DELAY = 800;
    private int mMode = PARTIAL_GROW;
    private boolean mRearrangeOnClose = false;
    private FolderIcon mFolderIcon;
    private int mMaxCountX;
    private int mMaxCountY;
    private Rect mNewSize = new Rect();
    private Rect mIconRect = new Rect();
    private ArrayList<View> mItemsInReadingOrder = new ArrayList<View>();
    private Drawable mIconDrawable;
    boolean mItemsInvalidated = false;
    private ShortcutInfo mCurrentDragInfo;
    private View mCurrentDragView;
    boolean mSuppressOnAdd = false;
    private int[] mTargetCell = new int[2];
    private int[] mPreviousTargetCell = new int[2];
    private int[] mEmptyCell = new int[2];
    private Alarm mReorderAlarm = new Alarm();
    private Alarm mOnExitAlarm = new Alarm();
    private TextView mFolderName;
    private int mFolderNameHeight;
    private Rect mHitRect = new Rect();
    private Rect mTempRect = new Rect();
    private boolean mDragInProgress = false;
    private boolean mDeleteFolderOnDropCompleted = false;
    private boolean mSuppressFolderDeletion = false;
    private boolean mItemAddedBackToSelfViaIcon = false;

    private boolean mIsEditingName = false;
    private InputMethodManager mInputMethodManager;

    private static String sDefaultFolderName;
    private static String sHintText;

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attribtues set containing the Workspace's customization values.
     */
    public Folder(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAlwaysDrawnWithCacheEnabled(false);
        mInflater = LayoutInflater.from(context);
        mIconCache = ((LauncherApplication)context.getApplicationContext()).getIconCache();
        mMaxCountX = LauncherModel.getCellCountX();
        mMaxCountY = LauncherModel.getCellCountY();

        mInputMethodManager = (InputMethodManager)
                mContext.getSystemService(Context.INPUT_METHOD_SERVICE);

        Resources res = getResources();
        mExpandDuration = res.getInteger(R.integer.config_folderAnimDuration);

        if (sDefaultFolderName == null) {
            sDefaultFolderName = res.getString(R.string.folder_name);
        }
        if (sHintText == null) {
            sHintText = res.getString(R.string.folder_hint_text);
        }

        mLauncher = (Launcher) context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = (CellLayout) findViewById(R.id.folder_content);
        mContent.setGridSize(0, 0);
        mFolderName = (TextView) findViewById(R.id.folder_name);

        // We find out how tall the text view wants to be (it is set to wrap_content), so that
        // we can allocate the appropriate amount of space for it.
        int measureSpec = MeasureSpec.UNSPECIFIED;
        mFolderName.measure(measureSpec, measureSpec);
        mFolderNameHeight = mFolderName.getMeasuredHeight();

        // We disable action mode for now since it messes up the view on phones
        mFolderName.setCustomSelectionActionModeCallback(mActionModeCallback);
        mFolderName.setCursorVisible(false);
        mFolderName.setOnEditorActionListener(this);
        mFolderName.setSelectAllOnFocus(true);
        mFolderName.setInputType(mFolderName.getInputType() |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    }

    private ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        public void onDestroyActionMode(ActionMode mode) {
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }
    };

    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof ShortcutInfo) {
            // refactor this code from Folder
            ShortcutInfo item = (ShortcutInfo) tag;
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            item.intent.setSourceBounds(new Rect(pos[0], pos[1],
                    pos[0] + v.getWidth(), pos[1] + v.getHeight()));
            mLauncher.startActivitySafely(item.intent, item);
        }
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mFolderName.getHitRect(mHitRect);
            if (mHitRect.contains((int) ev.getX(), (int) ev.getY()) && !mIsEditingName) {
                startEditingFolderName();
            }
        }
        return false;
    }

    public boolean onLongClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof ShortcutInfo) {
            ShortcutInfo item = (ShortcutInfo) tag;
            if (!v.isInTouchMode()) {
                return false;
            }

            mLauncher.dismissFolderCling(null);

            mLauncher.getWorkspace().onDragStartedWithItem(v);
            mLauncher.getWorkspace().beginDragShared(v, this);
            mIconDrawable = ((TextView) v).getCompoundDrawables()[1];

            mCurrentDragInfo = item;
            mEmptyCell[0] = item.cellX;
            mEmptyCell[1] = item.cellY;
            mCurrentDragView = v;

            mContent.removeView(mCurrentDragView);
            mInfo.remove(mCurrentDragInfo);
            mDragInProgress = true;
            mItemAddedBackToSelfViaIcon = false;
        }
        return true;
    }

    public boolean isEditingName() {
        return mIsEditingName;
    }

    public void startEditingFolderName() {
        mFolderName.setHint("");
        mFolderName.setCursorVisible(true);
        mIsEditingName = true;
    }

    public void dismissEditingName() {
        mInputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        doneEditingFolderName(true);
    }

    public void doneEditingFolderName(boolean commit) {
        mFolderName.setHint(sHintText);
        // Convert to a string here to ensure that no other state associated with the text field
        // gets saved.
        mInfo.setTitle(mFolderName.getText().toString());
        LauncherModel.updateItemInDatabase(mLauncher, mInfo);
        mFolderName.setCursorVisible(false);
        mFolderName.clearFocus();
        Selection.setSelection((Spannable) mFolderName.getText(), 0, 0);
        mIsEditingName = false;
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            dismissEditingName();
            return true;
        }
        return false;
    }

    public View getEditTextRegion() {
        return mFolderName;
    }

    public Drawable getDragDrawable() {
        return mIconDrawable;
    }

    /**
     * We need to handle touch events to prevent them from falling through to the workspace below.
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return true;
    }

    public void setDragController(DragController dragController) {
        mDragController = dragController;
    }

    void setFolderIcon(FolderIcon icon) {
        mFolderIcon = icon;
    }

    /**
     * @return the FolderInfo object associated with this folder
     */
    FolderInfo getInfo() {
        return mInfo;
    }

    void bind(FolderInfo info) {
        mInfo = info;
        ArrayList<ShortcutInfo> children = info.contents;
        ArrayList<ShortcutInfo> overflow = new ArrayList<ShortcutInfo>();
        setupContentForNumItems(children.size());
        int count = 0;
        for (int i = 0; i < children.size(); i++) {
            ShortcutInfo child = (ShortcutInfo) children.get(i);
            if (!createAndAddShortcut(child)) {
                overflow.add(child);
            } else {
                count++;
            }
        }

        // We rearrange the items in case there are any empty gaps
        setupContentForNumItems(count);

        // If our folder has too many items we prune them from the list. This is an issue 
        // when upgrading from the old Folders implementation which could contain an unlimited
        // number of items.
        for (ShortcutInfo item: overflow) {
            mInfo.remove(item);
            LauncherModel.deleteItemFromDatabase(mLauncher, item);
        }

        mItemsInvalidated = true;
        mInfo.addListener(this);

        if (!sDefaultFolderName.contentEquals(mInfo.title)) {
            mFolderName.setText(mInfo.title);
        } else {
            mFolderName.setText("");
        }
    }

    /**
     * Creates a new UserFolder, inflated from R.layout.user_folder.
     *
     * @param context The application's context.
     *
     * @return A new UserFolder.
     */
    static Folder fromXml(Context context) {
        return (Folder) LayoutInflater.from(context).inflate(R.layout.user_folder, null);
    }

    /**
     * This method is intended to make the UserFolder to be visually identical in size and position
     * to its associated FolderIcon. This allows for a seamless transition into the expanded state.
     */
    private void positionAndSizeAsIcon() {
        if (!(getParent() instanceof DragLayer)) return;

        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();

        if (mMode == PARTIAL_GROW) {
            setScaleX(0.8f);
            setScaleY(0.8f);
            setAlpha(0f);
        } else {
            mLauncher.getDragLayer().getDescendantRectRelativeToSelf(mFolderIcon, mIconRect);
            lp.width = mIconRect.width();
            lp.height = mIconRect.height();
            lp.x = mIconRect.left;
            lp.y = mIconRect.top;
            mContent.setAlpha(0);
        }
        mState = STATE_SMALL;
    }

    public void animateOpen() {
        positionAndSizeAsIcon();

        if (!(getParent() instanceof DragLayer)) return;

        ObjectAnimator oa;
        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();

        centerAboutIcon();
        if (mMode == PARTIAL_GROW) {
            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 1);
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f);
            oa = ObjectAnimator.ofPropertyValuesHolder(this, alpha, scaleX, scaleY);
        } else {
            PropertyValuesHolder width = PropertyValuesHolder.ofInt("width", mNewSize.width());
            PropertyValuesHolder height = PropertyValuesHolder.ofInt("height", mNewSize.height());
            PropertyValuesHolder x = PropertyValuesHolder.ofInt("x", mNewSize.left);
            PropertyValuesHolder y = PropertyValuesHolder.ofInt("y", mNewSize.top);
            oa = ObjectAnimator.ofPropertyValuesHolder(lp, width, height, x, y);
            oa.addUpdateListener(new AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator animation) {
                    requestLayout();
                }
            });

            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 1.0f);
            ObjectAnimator alphaOa = ObjectAnimator.ofPropertyValuesHolder(mContent, alpha);
            alphaOa.setDuration(mExpandDuration);
            alphaOa.setInterpolator(new AccelerateInterpolator(2.0f));
            alphaOa.start();
        }

        oa.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mState = STATE_ANIMATING;
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                mState = STATE_OPEN;
            }
        });
        oa.setDuration(mExpandDuration);
        oa.start();
    }

    public void animateClosed() {
        if (!(getParent() instanceof DragLayer)) return;

        ObjectAnimator oa;
        if (mMode == PARTIAL_GROW) {
            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 0);
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat("scaleX", 0.9f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat("scaleY", 0.9f);
            oa = ObjectAnimator.ofPropertyValuesHolder(this, alpha, scaleX, scaleY);
        } else {
            DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();

            PropertyValuesHolder width = PropertyValuesHolder.ofInt("width", mIconRect.width());
            PropertyValuesHolder height = PropertyValuesHolder.ofInt("height", mIconRect.height());
            PropertyValuesHolder x = PropertyValuesHolder.ofInt("x", mIconRect.left);
            PropertyValuesHolder y = PropertyValuesHolder.ofInt("y", mIconRect.top);
            oa = ObjectAnimator.ofPropertyValuesHolder(lp, width, height, x, y);
            oa.addUpdateListener(new AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator animation) {
                    requestLayout();
                }
            });

            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 0f);
            ObjectAnimator alphaOa = ObjectAnimator.ofPropertyValuesHolder(mContent, alpha);
            alphaOa.setDuration(mExpandDuration);
            alphaOa.setInterpolator(new DecelerateInterpolator(2.0f));
            alphaOa.start();
        }

        oa.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onCloseComplete();
                mState = STATE_SMALL;
            }
            @Override
            public void onAnimationStart(Animator animation) {
                mState = STATE_ANIMATING;
            }
        });
        oa.setDuration(mExpandDuration);
        oa.start();
    }

    void notifyDataSetChanged() {
        // recreate all the children if the data set changes under us. We may want to do this more
        // intelligently (ie just removing the views that should no longer exist)
        mContent.removeAllViewsInLayout();
        bind(mInfo);
    }

    public boolean acceptDrop(DragObject d) {
        final ItemInfo item = (ItemInfo) d.dragInfo;
        final int itemType = item.itemType;
        return ((itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION ||
                    itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT) &&
                    !isFull());
    }

    protected boolean findAndSetEmptyCells(ShortcutInfo item) {
        int[] emptyCell = new int[2];
        if (mContent.findCellForSpan(emptyCell, item.spanX, item.spanY)) {
            item.cellX = emptyCell[0];
            item.cellY = emptyCell[1];
            return true;
        } else {
            return false;
        }
    }

    protected boolean createAndAddShortcut(ShortcutInfo item) {
        final TextView textView =
            (TextView) mInflater.inflate(R.layout.application, this, false);
        textView.setCompoundDrawablesWithIntrinsicBounds(null,
                new FastBitmapDrawable(item.getIcon(mIconCache)), null, null);
        textView.setText(item.title);
        textView.setTag(item);

        textView.setOnClickListener(this);
        textView.setOnLongClickListener(this);

        // We need to check here to verify that the given item's location isn't already occupied
        // by another item. If it is, we need to find the next available slot and assign
        // it that position. This is an issue when upgrading from the old Folders implementation
        // which could contain an unlimited number of items.
        if (mContent.getChildAt(item.cellX, item.cellY) != null || item.cellX < 0 || item.cellY < 0
                || item.cellX >= mContent.getCountX() || item.cellY >= mContent.getCountY()) {
            if (!findAndSetEmptyCells(item)) {
                return false;
            }
        }

        CellLayout.LayoutParams lp =
            new CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY);
        boolean insert = false;
        mContent.addViewToCellLayout(textView, insert ? 0 : -1, (int)item.id, lp, true);
        return true;
    }

    public void onDragEnter(DragObject d) {
        mPreviousTargetCell[0] = -1;
        mPreviousTargetCell[1] = -1;
        mOnExitAlarm.cancelAlarm();
    }

    OnAlarmListener mReorderAlarmListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            realTimeReorder(mEmptyCell, mTargetCell);
        }
    };

    boolean readingOrderGreaterThan(int[] v1, int[] v2) {
        if (v1[1] > v2[1] || (v1[1] == v2[1] && v1[0] > v2[0])) {
            return true;
        } else {
            return false;
        }
    }

    private void realTimeReorder(int[] empty, int[] target) {
        boolean wrap;
        int startX;
        int endX;
        int startY;
        int delay = 0;
        float delayAmount = 30;
        if (readingOrderGreaterThan(target, empty)) {
            wrap = empty[0] >= mContent.getCountX() - 1;
            startY = wrap ? empty[1] + 1 : empty[1];
            for (int y = startY; y <= target[1]; y++) {
                startX = y == empty[1] ? empty[0] + 1 : 0;
                endX = y < target[1] ? mContent.getCountX() - 1 : target[0];
                for (int x = startX; x <= endX; x++) {
                    View v = mContent.getChildAt(x,y);
                    if (mContent.animateChildToPosition(v, empty[0], empty[1],
                            REORDER_ANIMATION_DURATION, delay)) {
                        empty[0] = x;
                        empty[1] = y;
                        delay += delayAmount;
                        delayAmount *= 0.9;
                    }
                }
            }
        } else {
            wrap = empty[0] == 0;
            startY = wrap ? empty[1] - 1 : empty[1];
            for (int y = startY; y >= target[1]; y--) {
                startX = y == empty[1] ? empty[0] - 1 : mContent.getCountX() - 1;
                endX = y > target[1] ? 0 : target[0];
                for (int x = startX; x >= endX; x--) {
                    View v = mContent.getChildAt(x,y);
                    if (mContent.animateChildToPosition(v, empty[0], empty[1],
                            REORDER_ANIMATION_DURATION, delay)) {
                        empty[0] = x;
                        empty[1] = y;
                        delay += delayAmount;
                        delayAmount *= 0.9;
                    }
                }
            }
        }
    }

    public void onDragOver(DragObject d) {
        float[] r = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset, d.dragView, null);
        mTargetCell = mContent.findNearestArea((int) r[0], (int) r[1], 1, 1, mTargetCell);

        if (mTargetCell[0] != mPreviousTargetCell[0] || mTargetCell[1] != mPreviousTargetCell[1]) {
            mReorderAlarm.cancelAlarm();
            mReorderAlarm.setOnAlarmListener(mReorderAlarmListener);
            mReorderAlarm.setAlarm(150);
            mPreviousTargetCell[0] = mTargetCell[0];
            mPreviousTargetCell[1] = mTargetCell[1];
        }
    }

    // This is used to compute the visual center of the dragView. The idea is that
    // the visual center represents the user's interpretation of where the item is, and hence
    // is the appropriate point to use when determining drop location.
    private float[] getDragViewVisualCenter(int x, int y, int xOffset, int yOffset,
            DragView dragView, float[] recycle) {
        float res[];
        if (recycle == null) {
            res = new float[2];
        } else {
            res = recycle;
        }

        // These represent the visual top and left of drag view if a dragRect was provided.
        // If a dragRect was not provided, then they correspond to the actual view left and
        // top, as the dragRect is in that case taken to be the entire dragView.
        // R.dimen.dragViewOffsetY.
        int left = x - xOffset;
        int top = y - yOffset;

        // In order to find the visual center, we shift by half the dragRect
        res[0] = left + dragView.getDragRegion().width() / 2;
        res[1] = top + dragView.getDragRegion().height() / 2;

        return res;
    }

    OnAlarmListener mOnExitAlarmListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            completeDragExit();
        }
    };

    public void completeDragExit() {
        mLauncher.closeFolder();
        mCurrentDragInfo = null;
        mCurrentDragView = null;
        mSuppressOnAdd = false;
        mRearrangeOnClose = true;
    }

    public void onDragExit(DragObject d) {
        // We only close the folder if this is a true drag exit, ie. not because a drop
        // has occurred above the folder.
        if (!d.dragComplete) {
            mOnExitAlarm.setOnAlarmListener(mOnExitAlarmListener);
            mOnExitAlarm.setAlarm(ON_EXIT_CLOSE_DELAY);
        }
        mReorderAlarm.cancelAlarm();
    }

    public void onDropCompleted(View target, DragObject d, boolean success) {
        if (success) {
            if (mDeleteFolderOnDropCompleted && !mItemAddedBackToSelfViaIcon) {
                replaceFolderWithFinalItem();
            }
        } else {
            // The drag failed, we need to return the item to the folder
            mFolderIcon.onDrop(d);

            // We're going to trigger a "closeFolder" which may occur before this item has
            // been added back to the folder -- this could cause the folder to be deleted
            if (mOnExitAlarm.alarmPending()) {
                mSuppressFolderDeletion = true;
            }
        }

        if (target != this) {
            if (mOnExitAlarm.alarmPending()) {
                mOnExitAlarm.cancelAlarm();
                completeDragExit();
            }
        }
        mDeleteFolderOnDropCompleted = false;
        mDragInProgress = false;
        mItemAddedBackToSelfViaIcon = false;
        mCurrentDragInfo = null;
        mCurrentDragView = null;
        mSuppressOnAdd = false;
    }

    public void notifyDrop() {
        if (mDragInProgress) {
            mItemAddedBackToSelfViaIcon = true;
        }
    }

    public boolean isDropEnabled() {
        return true;
    }

    public DropTarget getDropTargetDelegate(DragObject d) {
        return null;
    }

    private void setupContentDimension(int count) {
        ArrayList<View> list = getItemsInReadingOrder();

        int countX = mContent.getCountX();
        int countY = mContent.getCountY();
        boolean done = false;

        while (!done) {
            int oldCountX = countX;
            int oldCountY = countY;
            if (countX * countY < count) {
                // Current grid is too small, expand it
                if (countX <= countY && countX < mMaxCountX) {
                    countX++;
                } else if (countY < mMaxCountY) {
                    countY++;
                }
                if (countY == 0) countY++;
            } else if ((countY - 1) * countX >= count && countY >= countX) {
                countY = Math.max(0, countY - 1);
            } else if ((countX - 1) * countY >= count) {
                countX = Math.max(0, countX - 1);
            }
            done = countX == oldCountX && countY == oldCountY;
        }
        mContent.setGridSize(countX, countY);
        arrangeChildren(list);
    }

    public boolean isFull() {
        return getItemCount() >= mMaxCountX * mMaxCountY;
    }

    private void centerAboutIcon() {
        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();

        int width = getPaddingLeft() + getPaddingRight() + mContent.getDesiredWidth();
        int height = getPaddingTop() + getPaddingBottom() + mContent.getDesiredHeight()
                + mFolderNameHeight;
        DragLayer parent = (DragLayer) mLauncher.findViewById(R.id.drag_layer);

        parent.getDescendantRectRelativeToSelf(mFolderIcon, mTempRect);

        int centerX = mTempRect.centerX();
        int centerY = mTempRect.centerY();
        int centeredLeft = centerX - width / 2;
        int centeredTop = centerY - height / 2;

        // We first fetch the currently visible CellLayoutChildren
        CellLayout currentPage = mLauncher.getWorkspace().getCurrentDropLayout();
        CellLayoutChildren boundingLayout = currentPage.getChildrenLayout();
        Rect bounds = new Rect();
        parent.getDescendantRectRelativeToSelf(boundingLayout, bounds);

        // We need to bound the folder to the currently visible CellLayoutChildren
        int left = Math.min(Math.max(bounds.left, centeredLeft),
                bounds.left + bounds.width() - width);
        int top = Math.min(Math.max(bounds.top, centeredTop),
                bounds.top + bounds.height() - height);
        // If the folder doesn't fit within the bounds, center it about the desired bounds
        if (width >= bounds.width()) {
            left = bounds.left + (bounds.width() - width) / 2;
        }
        if (height >= bounds.height()) {
            top = bounds.top + (bounds.height() - height) / 2;
        }

        int folderPivotX = width / 2 + (centeredLeft - left);
        int folderPivotY = height / 2 + (centeredTop - top);
        setPivotX(folderPivotX);
        setPivotY(folderPivotY);
        int folderIconPivotX = (int) (mFolderIcon.getMeasuredWidth() *
                (1.0f * folderPivotX / width));
        int folderIconPivotY = (int) (mFolderIcon.getMeasuredHeight() *
                (1.0f * folderPivotY / height));
        mFolderIcon.setPivotX(folderIconPivotX);
        mFolderIcon.setPivotY(folderIconPivotY);

        if (mMode == PARTIAL_GROW) {
            lp.width = width;
            lp.height = height;
            lp.x = left;
            lp.y = top;
        } else {
            mNewSize.set(left, top, left + width, top + height);
        }
    }

    private void setupContentForNumItems(int count) {
        setupContentDimension(count);

        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();
        if (lp == null) {
            lp = new DragLayer.LayoutParams(0, 0);
            lp.customPosition = true;
            setLayoutParams(lp);
        }
        centerAboutIcon();
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getPaddingLeft() + getPaddingRight() + mContent.getDesiredWidth();
        int height = getPaddingTop() + getPaddingBottom() + mContent.getDesiredHeight()
                + mFolderNameHeight;

        int contentWidthSpec = MeasureSpec.makeMeasureSpec(mContent.getDesiredWidth(),
                MeasureSpec.EXACTLY);
        int contentHeightSpec = MeasureSpec.makeMeasureSpec(mContent.getDesiredHeight(),
                MeasureSpec.EXACTLY);
        mContent.measure(contentWidthSpec, contentHeightSpec);

        mFolderName.measure(contentWidthSpec,
                MeasureSpec.makeMeasureSpec(mFolderNameHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    private void arrangeChildren(ArrayList<View> list) {
        int[] vacant = new int[2];
        if (list == null) {
            list = getItemsInReadingOrder();
        }
        mContent.removeAllViews();

        for (int i = 0; i < list.size(); i++) {
            View v = list.get(i);
            mContent.getVacantCell(vacant, 1, 1);
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) v.getLayoutParams();
            lp.cellX = vacant[0];
            lp.cellY = vacant[1];
            ItemInfo info = (ItemInfo) v.getTag();
            info.cellX = vacant[0];
            info.cellY = vacant[1];
            boolean insert = false;
            mContent.addViewToCellLayout(v, insert ? 0 : -1, (int)info.id, lp, true);
            LauncherModel.addOrMoveItemInDatabase(mLauncher, info, mInfo.id, 0,
                    info.cellX, info.cellY);
        }
        mItemsInvalidated = true;
    }

    public int getItemCount() {
        return mContent.getChildrenLayout().getChildCount();
    }

    public View getItemAt(int index) {
        return mContent.getChildrenLayout().getChildAt(index);
    }

    private void onCloseComplete() {
        DragLayer parent = (DragLayer) getParent();
        parent.removeView(this);
        mDragController.removeDropTarget((DropTarget) this);
        clearFocus();

        if (mRearrangeOnClose) {
            setupContentForNumItems(getItemCount());
            mRearrangeOnClose = false;
        }
        if (getItemCount() <= 1) {
            if (!mDragInProgress && !mSuppressFolderDeletion) {
                replaceFolderWithFinalItem();
            } else if (mDragInProgress) {
                mDeleteFolderOnDropCompleted = true;
            }
        }
        mSuppressFolderDeletion = false;
    }

    private void replaceFolderWithFinalItem() {
        ItemInfo finalItem = null;

        if (getItemCount() == 1) {
            finalItem = mInfo.contents.get(0);
        }

        // Remove the folder completely
        CellLayout cellLayout = mLauncher.getCellLayout(mInfo.container, mInfo.screen);
        cellLayout.removeView(mFolderIcon);
        if (mFolderIcon instanceof DropTarget) {
            mDragController.removeDropTarget((DropTarget) mFolderIcon);
        }
        mLauncher.removeFolder(mInfo);

        if (finalItem != null) {
            LauncherModel.addOrMoveItemInDatabase(mLauncher, finalItem, mInfo.container,
                    mInfo.screen, mInfo.cellX, mInfo.cellY);
        }
        LauncherModel.deleteItemFromDatabase(mLauncher, mInfo);

        // Add the last remaining child to the workspace in place of the folder
        if (finalItem != null) {
            View child = mLauncher.createShortcut(R.layout.application, cellLayout,
                    (ShortcutInfo) finalItem);

            mLauncher.getWorkspace().addInScreen(child, mInfo.container, mInfo.screen, mInfo.cellX,
                    mInfo.cellY, mInfo.spanX, mInfo.spanY);
        }
    }

    public void onDrop(DragObject d) {
        ShortcutInfo item;
        if (d.dragInfo instanceof ApplicationInfo) {
            // Came from all apps -- make a copy
            item = ((ApplicationInfo) d.dragInfo).makeShortcut();
            item.spanX = 1;
            item.spanY = 1;
        } else {
            item = (ShortcutInfo) d.dragInfo;
        }
        // Dragged from self onto self, currently this is the only path possible, however
        // we keep this as a distinct code path.
        if (item == mCurrentDragInfo) {
            ShortcutInfo si = (ShortcutInfo) mCurrentDragView.getTag();
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) mCurrentDragView.getLayoutParams();
            si.cellX = lp.cellX = mEmptyCell[0];
            si.cellX = lp.cellY = mEmptyCell[1];
            mContent.addViewToCellLayout(mCurrentDragView, -1, (int)item.id, lp, true);
            if (d.dragView.hasDrawn()) {
                mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, mCurrentDragView);
            } else {
                mCurrentDragView.setVisibility(VISIBLE);
            }
            mItemsInvalidated = true;
            setupContentDimension(getItemCount());
            mSuppressOnAdd = true;
        }
        mInfo.add(item);
    }

    public void onAdd(ShortcutInfo item) {
        mItemsInvalidated = true;
        // If the item was dropped onto this open folder, we have done the work associated
        // with adding the item to the folder, as indicated by mSuppressOnAdd being set
        if (mSuppressOnAdd) return;
        if (!findAndSetEmptyCells(item)) {
            // The current layout is full, can we expand it?
            setupContentForNumItems(getItemCount() + 1);
            findAndSetEmptyCells(item);
        }
        createAndAddShortcut(item);
        LauncherModel.addOrMoveItemInDatabase(
                mLauncher, item, mInfo.id, 0, item.cellX, item.cellY);
    }

    public void onRemove(ShortcutInfo item) {
        mItemsInvalidated = true;
        // If this item is being dragged from this open folder, we have already handled
        // the work associated with removing the item, so we don't have to do anything here.
        if (item == mCurrentDragInfo) return;
        View v = getViewForInfo(item);
        mContent.removeView(v);
        if (mState == STATE_ANIMATING) {
            mRearrangeOnClose = true;
        } else {
            setupContentForNumItems(getItemCount());
        }
        if (getItemCount() <= 1) {
            replaceFolderWithFinalItem();
        }
    }

    private View getViewForInfo(ShortcutInfo item) {
        for (int j = 0; j < mContent.getCountY(); j++) {
            for (int i = 0; i < mContent.getCountX(); i++) {
                View v = mContent.getChildAt(i, j);
                if (v.getTag() == item) {
                    return v;
                }
            }
        }
        return null;
    }

    public void onItemsChanged() {
    }
    public void onTitleChanged(CharSequence title) {
    }

    public ArrayList<View> getItemsInReadingOrder() {
        return getItemsInReadingOrder(true);
    }

    public ArrayList<View> getItemsInReadingOrder(boolean includeCurrentDragItem) {
        if (mItemsInvalidated) {
            mItemsInReadingOrder.clear();
            for (int j = 0; j < mContent.getCountY(); j++) {
                for (int i = 0; i < mContent.getCountX(); i++) {
                    View v = mContent.getChildAt(i, j);
                    if (v != null) {
                        ShortcutInfo info = (ShortcutInfo) v.getTag();
                        if (info != mCurrentDragInfo || includeCurrentDragItem) {
                            mItemsInReadingOrder.add(v);
                        }
                    }
                }
            }
            mItemsInvalidated = false;
        }
        return mItemsInReadingOrder;
    }

    public void getLocationInDragLayer(int[] loc) {
        mLauncher.getDragLayer().getLocationInDragLayer(this, loc);
    }
}
