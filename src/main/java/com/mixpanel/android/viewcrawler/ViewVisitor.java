package com.mixpanel.android.viewcrawler;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.mixpanel.android.mpmetrics.MPConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@TargetApi(MPConfig.UI_FEATURES_MIN_API)
/* package */ abstract class ViewVisitor implements Pathfinder.Accumulator {

    /**
     * OnEvent will be fired when whatever the ViewVisitor installed fires
     * (For example, if the ViewVisitor installs watches for clicks, then OnEvent will be called
     * on click)
     */
    public interface OnEventListener {
        public void OnEvent(View host, String eventName, boolean debounce);
    }

    /**
     * Attempts to apply mutator to every matching view. Use this to update properties
     * in the view hierarchy. If accessor is non-null, it will be used to attempt to
     * prevent calls to the mutator if the property already has the intended value.
     */
    public static class PropertySetVisitor extends ViewVisitor {
        public PropertySetVisitor(List<Pathfinder.PathElement> path, Caller mutator, Caller accessor) {
            super(path);
            mMutator = mutator;
            mAccessor = accessor;
            mOriginalValueHolder = new Object[1];
            mOriginalValues = new WeakHashMap<View, Object>();
        }

        @Override
        public void cleanup() { // TODO this needs to be cleaned up not only on remove, but also on RESET (by id)
            for (Map.Entry<View, Object> original:mOriginalValues.entrySet()) {
                final View changedView = original.getKey();
                final Object originalValue = original.getValue();
                if (null != originalValue) {
                    mOriginalValueHolder[0] = originalValue;
                    mMutator.applyMethodWithArguments(changedView, mOriginalValueHolder);
                }
            }
        }

        @Override
        public void accumulate(View found) {
            if (null != mAccessor) {
                final Object[] setArgs = mMutator.getArgs();
                if (1 == setArgs.length) {
                    final Object desiredValue = setArgs[0];
                    final Object currentValue = mAccessor.applyMethod(found);

                    if (desiredValue == currentValue) {
                        return;
                    }

                    if (null != desiredValue) {
                        if (desiredValue instanceof Bitmap && currentValue instanceof Bitmap) {
                            final Bitmap desiredBitmap = (Bitmap) desiredValue;
                            final Bitmap currentBitmap = (Bitmap) currentValue;
                            if (desiredBitmap.sameAs(currentBitmap)) {
                                return;
                            }
                        } else if (desiredValue instanceof BitmapDrawable && currentValue instanceof BitmapDrawable) {
                            final Bitmap desiredBitmap = ((BitmapDrawable) desiredValue).getBitmap();
                            final Bitmap currentBitmap = ((BitmapDrawable) currentValue).getBitmap();
                            if (desiredBitmap != null && desiredBitmap.sameAs(currentBitmap)) {
                                return;
                            }
                        } else if (desiredValue.equals(currentValue)) {
                            return;
                        }
                    }

                    if (currentValue instanceof Bitmap ||
                            currentValue instanceof BitmapDrawable ||
                            mOriginalValues.containsKey(found)) {
                        ; // Cache exactly one non-image original value
                    } else {
                        mOriginalValueHolder[0] = currentValue;
                        if (mMutator.argsAreApplicable(mOriginalValueHolder)) {
                            mOriginalValues.put(found, currentValue);
                        } else {
                            mOriginalValues.put(found, null);
                        }
                    }
                }
            }

            mMutator.applyMethod(found);
        }

        protected String name() {
            return "Property Mutator";
        }

        private final Caller mMutator;
        private final Caller mAccessor;
        private final WeakHashMap<View, Object> mOriginalValues;
        private final Object[] mOriginalValueHolder;
    }

    public static class LayoutUpdateException extends IllegalStateException {
        public LayoutUpdateException(String message, JSONObject info) {
            super(message);
            mErrorInfo = info;
        }

        public JSONObject getErrorInfo() {
            return mErrorInfo;
        }

        private JSONObject mErrorInfo;
    }

    public static class LayoutUpdateVisitor extends ViewVisitor {
        public LayoutUpdateVisitor(List<Pathfinder.PathElement> path, LayoutRule args, String name) {
            super(path);
            mOriginalValues = new WeakHashMap<View, LayoutRule>();
            mArgs = args;
            mName = name;

            mHorizontalRules = new ArrayList<>(Arrays.asList(
                    RelativeLayout.LEFT_OF, RelativeLayout.RIGHT_OF,
                    RelativeLayout.ALIGN_LEFT, RelativeLayout.ALIGN_RIGHT
            ));
            mVerticalRules = new ArrayList<>(Arrays.asList(
                    RelativeLayout.ABOVE, RelativeLayout.BELOW,
                    RelativeLayout.ALIGN_BASELINE, RelativeLayout.ALIGN_TOP,
                    RelativeLayout.ALIGN_BOTTOM
            ));
        }

        @Override
        public void cleanup() {
            for (Map.Entry<View, LayoutRule> original:mOriginalValues.entrySet()) {
                final View changedView = original.getKey();
                final LayoutRule originalValue = original.getValue();
                setLayout(changedView, originalValue.verb, originalValue.anchor);
            }
        }

        @Override
        public void accumulate(View found) {
            final int newVerb = mArgs.verb;
            final int newAnchorId = mArgs.anchor;
            final RelativeLayout.LayoutParams currentParams = (RelativeLayout.LayoutParams)found.getLayoutParams();
            final int[] currentRules = currentParams.getRules();

            if (currentRules[newVerb] == newAnchorId) {
                return;
            }

            if (mOriginalValues.containsKey(found)) {
                ; // Cache exactly one
            } else {
                LayoutRule originalValue = new LayoutRule(newVerb, currentRules[newVerb]);
                mOriginalValues.put(found, originalValue);
            }

            try {
                setLayout(found, newVerb, newAnchorId);
            } catch (LayoutUpdateException e) {
                throw e;
            }
        }

        private void setLayout(View target, int verb, int anchorId) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)target.getLayoutParams();
            params.addRule(verb, anchorId);

            final ArrayList<Integer> rules;
            if (mHorizontalRules.contains(verb)) {
                rules = mHorizontalRules;
            } else if (mVerticalRules.contains(verb)) {
                rules = mVerticalRules;
            } else {
                rules = null;
            }

            if (rules != null && !verifyLayout(target, rules)) {
                JSONObject errorInfo = new JSONObject();
                try {
                    errorInfo.put("cid", mName);
                    errorInfo.put("error_type", "circular_dependency");
                } catch (JSONException e) {
                    ; // won't reach here
                }
                throw new LayoutUpdateException("Circular dependency detected!", errorInfo);
            }

            target.setLayoutParams(params);
        }

        private boolean verifyLayout(View target, ArrayList<Integer> rules) {
            ViewGroup parent = (ViewGroup) target.getParent();
            SparseArray<View> idToChild = new SparseArray<View>();

            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                idToChild.put(child.getId(), child);
            }

            ArrayMap<View, ArrayList<View>> dependencyGraph = new ArrayMap<View, ArrayList<View>>();
            for (int i = 0; i < idToChild.size(); i++) {
                final View child = idToChild.valueAt(i);
                final RelativeLayout.LayoutParams childLayoutParams = (RelativeLayout.LayoutParams) child.getLayoutParams();
                int[] layoutRules = childLayoutParams.getRules();

                ArrayList<View> dependencies = new ArrayList<View>();
                for (int rule : rules) {
                    int dependencyId = layoutRules[rule];
                    if (dependencyId > 0 && dependencyId != child.getId()) {
                        dependencies.add(idToChild.get(dependencyId));
                    }
                }

                dependencyGraph.put(child, dependencies);
            }

            return hasCycle(dependencyGraph);
        }

        private boolean hasCycle(ArrayMap<View, ArrayList<View>> dependencyGraph) {
            ArrayList<View> dfsStack = new ArrayList<View>();
            while (!dependencyGraph.isEmpty()) {
                View currentNode = dependencyGraph.keyAt(0);
                if (!subGraphHasCycle(dependencyGraph, currentNode, dfsStack)) {
                    return false;
                }
            }

            return true;
        }

        private boolean subGraphHasCycle(ArrayMap<View, ArrayList<View>> dependencyGraph,
                                         View currentNode, ArrayList<View> dfsStack) {
            if (dfsStack.contains(currentNode)) {
                return false;
            }

            if (dependencyGraph.containsKey(currentNode)) {
                ArrayList<View> dependencies = dependencyGraph.remove(currentNode);
                dfsStack.add(currentNode);

                for (int i = 0; i < dependencies.size(); i++) {
                    if (!subGraphHasCycle(dependencyGraph, dependencies.get(i), dfsStack)) {
                        return false;
                    }
                }

                dfsStack.remove(currentNode);
            }

            return true;
        }

        protected String name() { return "Layout Update"; }

        private final WeakHashMap<View, LayoutRule> mOriginalValues;
        private final LayoutRule mArgs;
        private final String mName;
        final ArrayList<Integer> mHorizontalRules;
        final ArrayList<Integer> mVerticalRules;
    }

    public static class LayoutRule {
        public LayoutRule(int v, int a) {
            verb = v;
            anchor = a;
        }

        public final int verb;
        public final int anchor;
    }

    /**
     * Adds an accessibility event, which will fire OnEvent, to every matching view.
     */
    public static class AddAccessibilityEventVisitor extends EventTriggeringVisitor {
        public AddAccessibilityEventVisitor(List<Pathfinder.PathElement> path, int accessibilityEventType, String eventName, OnEventListener listener) {
            super(path, eventName, listener, false);
            mEventType = accessibilityEventType;
            mWatching = new WeakHashMap<View, TrackingAccessibilityDelegate>();
        }

        @Override
        public void cleanup() {
            for (final Map.Entry<View, TrackingAccessibilityDelegate> entry:mWatching.entrySet()) {
                final View v = entry.getKey();
                final TrackingAccessibilityDelegate toCleanup = entry.getValue();
                final View.AccessibilityDelegate currentViewDelegate = getOldDelegate(v);
                if (currentViewDelegate == toCleanup) {
                    v.setAccessibilityDelegate(toCleanup.getRealDelegate());
                } else if (currentViewDelegate instanceof TrackingAccessibilityDelegate) {
                    final TrackingAccessibilityDelegate newChain = (TrackingAccessibilityDelegate) currentViewDelegate;
                    newChain.removeFromDelegateChain(toCleanup);
                } else {
                    // Assume we've been replaced, zeroed out, or for some other reason we're already gone.
                    // (This isn't too weird, for example, it's expected when views get recycled)
                }
            }
            mWatching.clear();
        }

        @Override
        public void accumulate(View found) {
            final View.AccessibilityDelegate realDelegate = getOldDelegate(found);
            if (realDelegate instanceof TrackingAccessibilityDelegate) {
                final TrackingAccessibilityDelegate currentTracker = (TrackingAccessibilityDelegate) realDelegate;
                if (currentTracker.willFireEvent(getEventName())) {
                    return; // Don't double track
                }
            }

            // We aren't already in the tracking call chain of the view
            final TrackingAccessibilityDelegate newDelegate = new TrackingAccessibilityDelegate(realDelegate);
            found.setAccessibilityDelegate(newDelegate);
            mWatching.put(found, newDelegate);
        }

        @Override
        protected String name() {
            return getEventName() + " event when (" + mEventType + ")";
        }

        private View.AccessibilityDelegate getOldDelegate(View v) {
            View.AccessibilityDelegate ret = null;
            try {
                Class<?> klass = v.getClass();
                Method m = klass.getMethod("getAccessibilityDelegate");
                ret = (View.AccessibilityDelegate) m.invoke(v);
            } catch (NoSuchMethodException e) {
                // In this case, we just overwrite the original.
            } catch (IllegalAccessException e) {
                // In this case, we just overwrite the original.
            } catch (InvocationTargetException e) {
                Log.w(LOGTAG, "getAccessibilityDelegate threw an exception when called.", e);
            }

            return ret;
        }

        private class TrackingAccessibilityDelegate extends View.AccessibilityDelegate {
            public TrackingAccessibilityDelegate(View.AccessibilityDelegate realDelegate) {
                mRealDelegate = realDelegate;
            }

            public View.AccessibilityDelegate getRealDelegate() {
                return mRealDelegate;
            }

            public boolean willFireEvent(final String eventName) {
                if (getEventName() == eventName) {
                    return true;
                } else if (mRealDelegate instanceof TrackingAccessibilityDelegate) {
                    return ((TrackingAccessibilityDelegate) mRealDelegate).willFireEvent(eventName);
                } else {
                    return false;
                }
            }

            public void removeFromDelegateChain(final TrackingAccessibilityDelegate other) {
                if (mRealDelegate == other) {
                    mRealDelegate = other.getRealDelegate();
                } else if (mRealDelegate instanceof TrackingAccessibilityDelegate) {
                    final TrackingAccessibilityDelegate child = (TrackingAccessibilityDelegate) mRealDelegate;
                    child.removeFromDelegateChain(other);
                } else {
                    // We can't see any further down the chain, just return.
                }
            }

            @Override
            public void sendAccessibilityEvent(View host, int eventType) {
                if (eventType == mEventType) {
                    fireEvent(host);
                }

                if (null != mRealDelegate) {
                    mRealDelegate.sendAccessibilityEvent(host, eventType);
                }
            }

            private View.AccessibilityDelegate mRealDelegate;
        }

        private final int mEventType;
        private final WeakHashMap<View, TrackingAccessibilityDelegate> mWatching;
    }

    /**
     * Installs a TextWatcher in each matching view. Does nothing if matching views are not TextViews.
     */
    public static class AddTextChangeListener extends EventTriggeringVisitor {
        public AddTextChangeListener(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener) {
            super(path, eventName, listener, true);
            mWatching = new HashMap<TextView, TextWatcher>();
        }

        @Override
        public void cleanup() {
            for (final Map.Entry<TextView, TextWatcher> entry:mWatching.entrySet()) {
                final TextView v = entry.getKey();
                final TextWatcher watcher = entry.getValue();
                v.removeTextChangedListener(watcher);
            }

            mWatching.clear();
        }

        @Override
        public void accumulate(View found) {
            if (found instanceof TextView) {
                final TextView foundTextView = (TextView) found;
                final TextWatcher watcher = new TrackingTextWatcher(foundTextView);
                final TextWatcher oldWatcher = mWatching.get(foundTextView);
                if (null != oldWatcher) {
                    foundTextView.removeTextChangedListener(oldWatcher);
                }
                foundTextView.addTextChangedListener(watcher);
                mWatching.put(foundTextView, watcher);
            }
        }

        @Override
        protected String name() {
            return getEventName() + " on Text Change";
        }

        private class TrackingTextWatcher implements TextWatcher {
            public TrackingTextWatcher(View boundTo) {
                mBoundTo = boundTo;
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ; // Nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ; // Nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                fireEvent(mBoundTo);
            }

            private final View mBoundTo;
        }

        private final Map<TextView, TextWatcher> mWatching;
    }

    /**
     * Monitors the view tree for the appearance of matching views where there were not
     * matching views before. Fires only once per traversal.
     */
    public static class ViewDetectorVisitor extends EventTriggeringVisitor {
        public ViewDetectorVisitor(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener) {
            super(path, eventName, listener, false);
            mSeen = false;
        }

        @Override
        public void cleanup() {
            ; // Do nothing, we don't have anything to leak :)
        }

        @Override
        public void accumulate(View found) {
            if (found != null && !mSeen) {
                fireEvent(found);
            }

            mSeen = (found != null);
        }

        @Override
        protected String name() {
            return getEventName() + " when Detected";
        }

        private boolean mSeen;
    }

    private static abstract class EventTriggeringVisitor extends ViewVisitor {
        public EventTriggeringVisitor(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener, boolean debounce) {
            super(path);
            mListener = listener;
            mEventName = eventName;
            mDebounce = debounce;
        }

        protected void fireEvent(View found) {
            mListener.OnEvent(found, mEventName, mDebounce);
        }

        protected String getEventName() {
            return mEventName;
        }

        private final OnEventListener mListener;
        private final String mEventName;
        private final boolean mDebounce;
    }

    /**
     * Scans the View hierarchy below rootView, applying it's operation to each matching child view.
     */
    public void visit(View rootView) throws LayoutUpdateException {
        mPathfinder.findTargetsInRoot(rootView, mPath, this);
    }

    /**
     * Removes listeners and frees resources associated with the visitor. Once cleanup is called,
     * the ViewVisitor should not be used again.
     */
    public abstract void cleanup();

    protected ViewVisitor(List<Pathfinder.PathElement> path) {
        mPath = path;
        mPathfinder = new Pathfinder();
    }

    protected abstract String name();

    private final List<Pathfinder.PathElement> mPath;
    private final Pathfinder mPathfinder;

    private static final String LOGTAG = "MixpanelAPI.ViewVisitor";
}
