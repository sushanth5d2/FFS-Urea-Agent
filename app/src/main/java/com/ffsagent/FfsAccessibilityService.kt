package com.ffsagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.WindowManager
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class FfsAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: FfsAccessibilityService? = null
        @Volatile private var requested = false
        const val ACTION_STATUS = "com.ffsagent.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"

        fun requestStart() { requested = true; instance?.resetAutomation(); instance?.announce("AGENT RUNNING", "Waiting for FFS login/home screen") }
        fun requestStop() { requested = false; instance?.stopAutomation("Stopped by user") }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var currentState = State.WAIT_LOGIN
    private var monitorTicks = 0
    private var retailerSelected = false
    private var landSelectionStarted = false
    private var landsDone = false
    private var lastFertilizerActionAt = 0L
    private var minusAttemptIndex = 0
    private var ureaOptionAttemptIndex = 0
    private var ureaKeyboardDismissed = false
    private var ureaSearchTyped = false
    private var fertilizerInitialScrollDone = false
    private var ureaPickerScrollAttempts = 0
    private var ureaSelectionTapAttempts = 0
    private var fertilizerScrollAttempts = 0
    private var landScrollAttempts = 0
    private var lastLandPickerSignature = ""
    private var landNoMovementCount = 0
    private var landDoneAt = 0L
    private var lastLandScrollAt = 0L
    private var landScrollInProgress = false
    private var selectAllLandAttempted = false
    private var expectedLandCount = 0
    private var landsSelectedCount = 0
    private var ureaSelectorOpened = false
    private var ureaSelectorBounds: Rect? = null
    private var ureaProductSelected = false
    private var ureaNextClicked = false
    private var gfrYesProceedClicked = false
    private var gfrOnlySelected = false
    private var gfrContinueClicked = false
    private var retailerNoResultTicks = 0
    private var lastRetailerCheckAt = 0L
    private var retailerSearchValue = ""
    private var retailerSearchSubmitted = false
    private var receiverMobileValue = ""
    private var receiverHandled = false
    private var retailerUreaSelected = false
    private var npksRemoved = false
    private var dapRemoved = false
    private var mopRemoved = false
    private var overlay: TextView? = null
    private var overlayManager: WindowManager? = null
    private var lastAnnounced = ""
    private var lastNotificationAt = 0L
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private enum class State {
        WAIT_LOGIN, HOME, MY_FARM, LAND_SELECT, GFR, GFR_DIALOG,
        ADD_FERTILIZER, REMOVE_NPKS, REMOVE_DAP, REMOVE_MOP,
        SELECT_UREA_PRODUCT, RETAILER, RETAILER_AVAILABLE, RECEIVER, SUBMITTING, SUCCESS, ERROR
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createChannel()
        createOverlay()
        announce("AGENT READY", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!requested) return
        val target = prefs.getString("target_package", "")?.trim().orEmpty()
        if (target.isEmpty()) return
        if (event?.packageName?.toString() != target) return
        handler.removeCallbacksAndMessages(null)
        handler.post { tick() }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    private fun resetAutomation() {
        handler.removeCallbacksAndMessages(null)
        currentState = State.WAIT_LOGIN
        monitorTicks = 0
        retailerSelected = false
        landSelectionStarted = false
        landsDone = false
        lastFertilizerActionAt = 0L
        minusAttemptIndex = 0
        ureaSelectorOpened = false
        ureaSelectorBounds = null
        ureaProductSelected = false
        ureaNextClicked = false
        gfrYesProceedClicked = false
        gfrOnlySelected = false
        gfrContinueClicked = false
        ureaOptionAttemptIndex = 0
        ureaKeyboardDismissed = false
        ureaSearchTyped = false
        ureaPickerScrollAttempts = 0
        ureaSelectionTapAttempts = 0
        fertilizerInitialScrollDone = false
        fertilizerScrollAttempts = 0
        landScrollAttempts = 0
        lastLandPickerSignature = ""
        landNoMovementCount = 0
        lastLandScrollAt = 0L
        landScrollInProgress = false
        selectAllLandAttempted = false
        expectedLandCount = 0
        landsSelectedCount = 0
        retailerNoResultTicks = 0
        lastRetailerCheckAt = 0L
        retailerSearchValue = prefs.getString("retailer_location", "Korlagudem")?.trim().orEmpty().ifEmpty { "Korlagudem" }
        retailerSearchSubmitted = false
        receiverMobileValue = prefs.getString("receiver_mobile", "")?.trim().orEmpty()
        receiverHandled = false
        retailerUreaSelected = false
        npksRemoved = false
        dapRemoved = false
        mopRemoved = false
        lastAnnounced = ""
        announce("AGENT RUNNING", "Automation state reset. Waiting for FFS")
    }

    private fun tick() {
        if (!requested) return
        val root = rootInActiveWindow ?: run { announce("WAITING", "FFS window not available"); schedule(); return }
        val target = prefs.getString("target_package", "")?.trim().orEmpty()
        if (root.packageName?.toString() != target) { schedule(); return }

        val now = System.currentTimeMillis()
        if (now - lastActionAt < 90) { schedule(); return }

        val texts = collectTexts(root)
        val all = texts.joinToString(" | ").lowercase(Locale.ROOT)

        if (containsAny(all, "booking successful", "successfully booked", "booking confirmed", "application submitted successfully", "fertilizer application submitted", "booking submitted successfully")) {
            currentState = State.SUCCESS
            announce("SUCCESS", "Booking confirmation detected")
            stopAutomation("Booking successful")
            return
        }

        // LOGIN -> HOME. OTP is never read or entered by the agent.
        if (looksLikeHome(all)) {
            currentState = State.HOME
            announce("HOME", "Login detected. Looking for My Farm")
            val myFarm = findClickableText(root, "My Farm") ?: findNodeContainingText(root, "My Farm")
            if (myFarm != null && clickNode(myFarm)) {
                announce("HOME", "Clicked My Farm")
            } else {
                announce("WAITING", "Home detected; My Farm is not clickable yet")
            }
            schedule(); return
        }

        // Terminal post-Done state. Accessibility may deliver stale events from
        // the old picker; never scroll/select lands again after Done. Wait for
        // the Farm screen and immediately continue to Apply for Fertilizers.
        if (landDoneAt > 0L) {
            currentState = State.MY_FARM
            val applyAfterDone = findClickableText(root, "Apply for Fertilizers")
                ?: findNodeContainingText(root, "Apply for Fertilizers")
            if (applyAfterDone != null && clickNode(applyAfterDone)) {
                announce("FERTILIZER", "Done completed. Clicked Apply for Fertilizers")
                landDoneAt = 0L
                schedule()
                return
            }
            announce("WAITING", "Land selection complete. Waiting for Apply for Fertilizers; land scrolling disabled")
            schedule()
            return
        }

        // LAND SELECTION
        // The number of checkboxes to select is determined from "Verified Holdings".
        // We never use Select All because the FFS custom picker can expose that control
        // without reliably selecting every row. We select individual unchecked land
        // checkboxes and stop immediately when the expected count is reached.
        if (landSelectionStarted) {
            currentState = State.LAND_SELECT

            if (expectedLandCount <= 0) {
                expectedLandCount = readVerifiedHoldingCount(root)
                if (expectedLandCount > 0) {
                    announce("LANDS", "Verified Holdings count detected: $expectedLandCount")
                } else {
                    announce("LANDS", "Waiting for Verified Holdings count")
                    schedule()
                    return
                }
            }

            if (landsSelectedCount >= expectedLandCount) {
                val done = findClickableText(root, "Done") ?: findNodeContainingText(root, "Done")
                if (done != null && clickNode(done)) {
                    landsDone = true
                    landSelectionStarted = false
                    landScrollAttempts = 0
                    landNoMovementCount = 0
                    landScrollInProgress = false
                    landDoneAt = System.currentTimeMillis()
                    lastLandPickerSignature = ""
                    announce("LANDS", "Selected $landsSelectedCount/$expectedLandCount lands. Done clicked; land scrolling disabled")
                } else {
                    announce("LANDS", "Selected $landsSelectedCount/$expectedLandCount lands. Waiting for Done")
                }
                schedule()
                return
            }

            // Give a just-completed scroll time to publish its new accessibility tree.
            if (landScrollInProgress) {
                if (System.currentTimeMillis() - lastLandScrollAt < 450L) {
                    schedule()
                    return
                }
                val after = landPickerContentSignature(root)
                if (after == lastLandPickerSignature) {
                    landNoMovementCount++
                    if (landNoMovementCount >= 2) {
                        announce("LANDS", "Land list stopped moving at $landsSelectedCount/$expectedLandCount")
                        schedule()
                        return
                    }
                } else {
                    landNoMovementCount = 0
                    lastLandPickerSignature = after
                }
                landScrollInProgress = false
            }

            // Select one unchecked land checkbox currently exposed.
            if (selectVisibleLandEntry(root)) {
                landsSelectedCount++
                landScrollInProgress = false
                landNoMovementCount = 0
                announce("LANDS", "Selected land checkbox $landsSelectedCount/$expectedLandCount")
                schedule()
                return
            }

            // No unchecked checkbox is visible, but more lands are required.
            // Therefore scroll the land list to expose the next batch. Do not scroll
            // once the expected count has been reached.
            val scrollTarget = findVisibleScrollable(root)
            if (scrollTarget != null) {
                val before = landPickerContentSignature(root)
                if (landScrollInProgress && System.currentTimeMillis() - lastLandScrollAt < 450L) {
                    schedule()
                    return
                }

                val scrolled = scrollTarget.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (scrolled) {
                    landScrollAttempts++
                    landScrollInProgress = true
                    lastLandScrollAt = System.currentTimeMillis()
                    lastLandPickerSignature = before
                    announce("LANDS", "Selected $landsSelectedCount/$expectedLandCount. Scrolling to next lands (#$landScrollAttempts)")
                    schedule()
                    return
                }
                announce("LANDS", "Land list reached end before expected count: $landsSelectedCount/$expectedLandCount")
            } else {
                // Custom picker fallback: one controlled gesture, then wait for the
                // next accessibility tree. The count gate prevents endless scrolling.
                val signature = landPickerContentSignature(root)
                if (!landScrollInProgress) {
                    landScrollAttempts++
                    lastLandPickerSignature = signature
                    landScrollInProgress = true
                    lastLandScrollAt = System.currentTimeMillis()
                    announce("LANDS", "Selected $landsSelectedCount/$expectedLandCount. Scrolling to next lands (#$landScrollAttempts)")
                    if (!scrollForward(root)) {
                        landScrollInProgress = false
                        announce("LANDS", "Could not scroll land list")
                    }
                    schedule()
                    return
                }

                if (signature == lastLandPickerSignature) {
                    landNoMovementCount++
                } else {
                    landNoMovementCount = 0
                    lastLandPickerSignature = signature
                }

                if (landNoMovementCount < 2) {
                    schedule()
                    return
                }

                announce("LANDS", "Land list did not expose more checkboxes: $landsSelectedCount/$expectedLandCount")
            }

            schedule()
            return
        }

        if (isMyFarmScreen(all)) {
            currentState = State.MY_FARM
            if (!landsDone) {
                announce("MY FARM", "Looking for Select Land")
                val selectLand = findClickableText(root, "Select Land") ?: findNodeContainingText(root, "Select Land")
                if (selectLand != null && clickNode(selectLand)) {
                    expectedLandCount = readVerifiedHoldingCount(root)
                    landsSelectedCount = 0
                    landSelectionStarted = true
                    selectAllLandAttempted = false
                    announce(
                        "LANDS",
                        if (expectedLandCount > 0) {
                            "Clicked Select Land. Verified Holdings = $expectedLandCount. Selecting exactly $expectedLandCount land checkbox(es)"
                        } else {
                            "Clicked Select Land. Verified Holdings count not exposed yet; reading land list"
                        }
                    )
                } else {
                    announce("RETRYING", "Select Land visible but click failed")
                }
            } else {
                announce("MY FARM", "Lands already selected. Looking for Apply for Fertilizers")
                val apply = findClickableText(root, "Apply for Fertilizers") ?: findNodeContainingText(root, "Apply for Fertilizers")
                if (apply != null && clickNode(apply)) {
                    announce("FERTILIZER", "Clicked Apply for Fertilizers")
                } else {
                    announce("WAITING", "Apply for Fertilizers not clickable yet")
                }
            }
            schedule(); return
        }

        // Some versions expose the selected land screen without the full Farm Overview text.
        if (landsDone && isMyFarmAfterSelection(all)) {
            currentState = State.MY_FARM
            announce("MY FARM", "Looking for Apply for Fertilizers")
            val apply = findClickableText(root, "Apply for Fertilizers") ?: findNodeContainingText(root, "Apply for Fertilizers")
            if (apply != null && clickNode(apply)) announce("FERTILIZER", "Clicked Apply for Fertilizers")
            else announce("WAITING", "Apply for Fertilizers not clickable yet")
            schedule(); return
        }

        if (hasText(root, "Yes, Proceed") && !gfrYesProceedClicked) {
            currentState = State.GFR
            announce("GFR", "Yes, Proceed available. Clicking it once")
            if (clickText(root, "Yes, Proceed")) {
                gfrYesProceedClicked = true
                schedule(); return
            }
        }

        if (all.contains("gfr recommended quantity") && !gfrYesProceedClicked) {
            currentState = State.GFR
            announce("GFR", "Recommended quantity screen detected")
            if (clickText(root, "Yes, Proceed")) {
                gfrYesProceedClicked = true
                schedule(); return
            }
        }

        if (all.contains("how would you like to continue") && all.contains("recommended gfr only")) {
            currentState = State.GFR_DIALOG

            // This first-run dialog is sometimes rendered as a custom UI where the
            // radio/text nodes are visible but are not exposed as clickable
            // accessibility nodes. Use the visible text bounds as a deterministic
            // gesture fallback, and never leave this state just because ACTION_CLICK
            // is unavailable.
            if (!gfrOnlySelected) {
                val option = findNodeContainingText(root, "Recommended GFR Only")
                val alreadyChecked = option?.isCheckable == true && option.isChecked
                if (alreadyChecked) {
                    gfrOnlySelected = true
                    announce("GFR", "Recommended GFR Only is already selected")
                } else if (option != null && clickVisibleGfrOption(option)) {
                    gfrOnlySelected = true
                    announce("GFR", "Recommended GFR Only selected")
                    schedule(); return
                } else {
                    announce("WAITING", "Recommended GFR Only is visible; retrying its visible radio/row tap")
                    schedule(); return
                }
            }

            if (gfrOnlySelected && !gfrContinueClicked) {
                val continueNode = findClickableText(root, "Continue")
                    ?: findNodeWithText(root, "Continue")
                    ?: findNodeContainingText(root, "Continue")
                if (continueNode != null && clickVisibleGfrContinue(continueNode)) {
                    gfrContinueClicked = true
                    announce("GFR", "Recommended GFR Only selected. Clicked Continue")
                    schedule(); return
                }
                announce("WAITING", "Recommended GFR Only selected; retrying visible Continue button")
            }
            schedule(); return
        }

        if (all.contains("are you sure you want to delete this product group")) {
            // The delete dialog can arrive before the next accessibility tree is
            // published. Complete the product state here so we do not re-enter
            // the fertilizer-removal step after a successful delete.
            val product = when (currentState) {
                State.REMOVE_NPKS -> "NPKS"
                State.REMOVE_DAP -> "DAP"
                State.REMOVE_MOP -> "MOP"
                else -> null
            }
            announce("DELETE", "Delete confirmation detected. Clicking Delete${product?.let { " for $it" } ?: ""}")
            if (clickText(root, "Delete")) {
                when (product) {
                    "NPKS" -> npksRemoved = true
                    "DAP" -> dapRemoved = true
                    "MOP" -> mopRemoved = true
                }
                minusAttemptIndex = 0
                fertilizerScrollAttempts = 0
                lastFertilizerActionAt = System.currentTimeMillis()
                currentState = State.ADD_FERTILIZER
                announce("DELETE", product?.let { "$it removed successfully. Moving to next fertilizer/product" } ?: "Delete completed")
            } else {
                announce("RETRYING", "Delete button found but click could not be dispatched")
            }
            schedule(); return
        }

        if (all.contains("add fertilizer") ||
            (all.contains("select fertilizer") && all.contains("urea") && all.contains("total selected"))) {
            currentState = State.ADD_FERTILIZER
            processFertilizers(root, all)
            schedule(); return
        }

        if (all.contains("select retailer") || (all.contains("total retailers") && all.contains("search village or centre"))) {
            if (currentState != State.RETAILER && currentState != State.RETAILER_AVAILABLE) {
                retailerSelected = false
                retailerSearchSubmitted = false
                retailerUreaSelected = false
            }
            currentState = if (retailerSelected) State.RETAILER_AVAILABLE else State.RETAILER
            monitorRetailer(root)
            schedule(); return
        }

        if (currentState == State.SUBMITTING && containsAny(all, "are you sure", "confirm submission", "confirm booking") && hasText(root, "Submit")) {
            announce("SUBMITTING", "Confirmation dialog detected")
            clickText(root, "Submit")
            schedule(); return
        }

        if (all.contains("receiver details") || (all.contains("farmer himself") && all.contains("submit"))) {
            currentState = State.RECEIVER
            monitorReceiver(root)
            schedule(); return
        }

        announce("WAITING", "Screen detected but no matching workflow step yet")
        schedule()
    }

    private fun processFertilizers(root: AccessibilityNodeInfo, all: String) {
        // After GFR Continue the Add Fertilizer page opens with Urea at the top.
        // For NPKS/DAP/MOP, visibility of the product name is NOT enough: the
        // quantity row and the actual minus control must also be visible. If the
        // product title is visible but the minus is below the viewport, perform
        // small manual-like scrolls until that minus control becomes visible.
        if (!npksRemoved || !dapRemoved || !mopRemoved) {
            val actionable = when {
                !npksRemoved && isFertilizerActionable(root, "NPKS") -> Pair("NPKS", State.REMOVE_NPKS)
                !dapRemoved && isFertilizerActionable(root, "DAP") -> Pair("DAP", State.REMOVE_DAP)
                !mopRemoved && isFertilizerActionable(root, "MOP") -> Pair("MOP", State.REMOVE_MOP)
                else -> null
            }

            if (actionable != null) {
                if (currentState != actionable.second) {
                    minusAttemptIndex = 0
                    fertilizerScrollAttempts = 0
                }
                currentState = actionable.second
                announce("FERTILIZER", "${actionable.first} and its − control are visible. Stopping scroll and deleting it")
                removeUntilZero(root, actionable.first)
                return
            }

            val productNeedingControls = when {
                !npksRemoved && isFertilizerVisible(root, "NPKS") -> "NPKS"
                !dapRemoved && isFertilizerVisible(root, "DAP") -> "DAP"
                !mopRemoved && isFertilizerVisible(root, "MOP") -> "MOP"
                else -> null
            }

            if (productNeedingControls != null) {
                currentState = when (productNeedingControls) {
                    "NPKS" -> State.REMOVE_NPKS
                    "DAP" -> State.REMOVE_DAP
                    else -> State.REMOVE_MOP
                }
                if (fertilizerScrollAttempts < 2) {
                    fertilizerScrollAttempts++
                    announce("FERTILIZER", "$productNeedingControls is visible but − is not visible. Small scroll (#$fertilizerScrollAttempts)")
                    if (!scrollFertilizerShort()) {
                        announce("WAITING", "Could not dispatch small scroll for $productNeedingControls")
                    }
                } else {
                    announce("WAITING", "$productNeedingControls is visible but its − control is still unavailable after 2 small scrolls")
                }
                schedule()
                return
            }

            if (fertilizerScrollAttempts < 2) {
                fertilizerScrollAttempts++
                announce("FERTILIZER", "NPKS/DAP/MOP not visible yet. Small scroll (#$fertilizerScrollAttempts)")
                if (!scrollFertilizerShort()) announce("WAITING", "Could not dispatch fertilizer scroll")
                schedule()
                return
            }

            announce("WAITING", "NPKS/DAP/MOP are not exposed after 2 small scrolls")
            schedule()
            return
        }

        // NPKS/DAP/MOP have all been removed. Never search for them again.
        fertilizerScrollAttempts = 0
        fertilizerInitialScrollDone = true

        // Urea product selection: dropdown-only. Do NOT press Back and do NOT
        // dismiss the keyboard because Back can close the custom dropdown itself.
        if (!ureaProductSelected) {
            currentState = State.SELECT_UREA_PRODUCT

            // Always verify the selected field first, even while the dropdown is
            // marked open. The custom picker can close immediately after a real tap.
            // This prevents an open/close loop before checking the actual field value.
            if (hasSelectedUreaProduct(root)) {
                ureaProductSelected = true
                ureaSelectorOpened = false
                ureaSelectorBounds = null
                ureaPickerScrollAttempts = 0
                ureaSelectionTapAttempts = 0
                ureaNextClicked = false
                announce("UREA", "Select Product field verified: Neem Coated Urea (45 Kg) is selected")
                schedule()
                return
            }

            if (ureaSelectorOpened) {
                // The FFS picker is a custom view: in some snapshots the visible
                // Neem row is painted on screen but is NOT exposed as an
                // AccessibilityNodeInfo. Do not close/reopen the picker and do not
                // scroll it. When the dropdown is open, the second option is the
                // visible Neem Coated Urea (45 Kg) row. Tap that row directly using
                // the Select Product field bounds as the anchor.
                val option = findUreaOption(root)
                ureaSelectionTapAttempts++
                if (option != null && isNodeVisible(option)) {
                    announce("UREA", "Neem Coated Urea (45 Kg) is visible. Tapping the visible option")
                    if (clickProductOption(option)) {
                        lastActionAt = System.currentTimeMillis()
                        announce("UREA", "Neem option tapped. Waiting for selected product verification")
                    } else {
                        announce("WAITING", "Visible Neem option could not be tapped; leaving dropdown open")
                    }
                    schedule()
                    return
                }

                // Fallback for the exact case shown in the screenshot: the option is
                // visually present but absent from the accessibility tree. The popup
                // has two fixed-height rows directly below the Select Product field;
                // tap the center of the second row (Neem) without opening/closing or
                // dismissing the keyboard.
                if (tapVisibleNeemByDropdownGeometry(root)) {
                    lastActionAt = System.currentTimeMillis()
                    announce("UREA", "Neem option is visible but not exposed in accessibility tree. Tapped visible second dropdown row")
                } else {
                    announce("WAITING", "Neem option is not tappable yet; leaving the open dropdown unchanged")
                }
                schedule()
                return
            }

            // No dropdown is open and the field is still unselected: open it.
            val selector = findClickableText(root, "Select Product") ?: findNodeContainingText(root, "Select Product")
            if (selector != null) {
                announce("UREA", "Opening Select Product dropdown")
                if (clickNode(selector)) {
                    ureaSelectorBounds = Rect().also { selector.getBoundsInScreen(it) }
                    ureaSelectorOpened = true
                    ureaPickerScrollAttempts = 0
                    ureaSelectionTapAttempts = 0
                    ureaNextClicked = false
                    lastActionAt = System.currentTimeMillis()
                    announce("UREA", "Dropdown opened. Keyboard state is left unchanged")
                } else {
                    announce("RETRYING", "Select Product is visible but could not be clicked")
                }
                schedule()
                return
            }

            announce("WAITING", "Waiting for Urea Select Product field")
            schedule()
            return
        }

        if (ureaProductSelected) {
            currentState = State.SELECT_UREA_PRODUCT

            // The Select Product field keeps the soft keyboard open after Neem is
            // selected. On the FFS screen the Next button is below that keyboard,
            // so it is not actually tappable yet. Dismiss the keyboard ONLY after
            // Neem has been verified as selected. We never dismiss it while the
            // product dropdown is open.
            if (!ureaKeyboardDismissed) {
                announce("UREA", "Neem Coated Urea selected. Dismissing keyboard to expose Next button")
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    ureaKeyboardDismissed = true
                    lastActionAt = System.currentTimeMillis()
                    announce("UREA", "Keyboard dismissed. Waiting for visible Next button")
                } else {
                    announce("WAITING", "Could not dismiss keyboard; Next button remains behind keyboard")
                }
                schedule()
                return
            }

            if (!ureaNextClicked) {
                val next = findClickableText(root, "Next") ?: findNodeWithText(root, "Next")
                if (next != null && clickNode(next)) {
                    ureaNextClicked = true
                    announce("UREA", "Neem Coated Urea selected. Clicked visible Next once")
                } else {
                    announce("UREA", "Neem Coated Urea selected. Waiting for visible Next")
                }
            }
            schedule()
            return
        }

        if (hasText(root, "Select Product")) {
            currentState = State.SELECT_UREA_PRODUCT
            announce("UREA", "Opening Urea product selector")
            val selector = findClickableText(root, "Select Product") ?: findNodeContainingText(root, "Select Product")
            if (selector != null && clickNode(selector)) {
                ureaSelectorOpened = true
                ureaKeyboardDismissed = false
                ureaSearchTyped = false
                ureaPickerScrollAttempts = 0
                ureaSelectionTapAttempts = 0
                ureaNextClicked = false
                announce("UREA", "Urea product selector opened. Selecting Neem Coated Urea from dropdown only; keyboard stays open")
            } else {
                announce("RETRYING", "Select Product is visible but could not be clicked")
            }
            schedule()
            return
        }

        announce("UREA", "Waiting for Urea product selector")
        schedule()
    }

    private fun isRemoved(product: String): Boolean = when (product.uppercase(Locale.ROOT)) {
        "NPKS" -> npksRemoved
        "DAP" -> dapRemoved
        "MOP" -> mopRemoved
        else -> false
    }

    private fun isFertilizerVisible(root: AccessibilityNodeInfo, product: String): Boolean {
        val title = findNodeWithText(root, product) ?: return false
        return isNodeVisible(title)
    }

    private fun isFertilizerActionable(root: AccessibilityNodeInfo, product: String): Boolean {
        val title = findNodeWithText(root, product) ?: return false
        if (!isNodeVisible(title)) return false
        val group = findProductGroup(root, product) ?: return false
        if (!isNodeVisible(group)) return false
        val quantity = findQuantityNumber(group) ?: return false
        val quantityNode = findQuantityNode(group)
        val minus = findMinus(group) ?: quantityNode?.let { findMinusNearQuantity(group, it) }
        return minus != null && quantity >= 0
    }

    private fun scrollFertilizerShort(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.50f
        // Small manual-like downward scroll, matching the movement that exposes
        // the NPKS/DAP/MOP controls on the user's FFS screen.
        val startY = dm.heightPixels * 0.73f
        val endY = dm.heightPixels * 0.53f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 140L))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        if (ok) lastActionAt = System.currentTimeMillis()
        return ok
    }

    private fun findUreaSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (best != null || !n.isEditable || !isNodeVisible(n)) return@collect
            val hint = n.hintText?.toString().orEmpty()
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val combined = "$hint $text $desc"
            if (combined.contains("search", true) || combined.contains("product", true) || combined.contains("fertilizer", true)) {
                best = n
            }
        }
        if (best != null) return best
        var first: AccessibilityNodeInfo? = null
        collect(root) { n -> if (first == null && n.isEditable && isNodeVisible(n)) first = n }
        return first
    }

    private fun removeUntilZero(root: AccessibilityNodeInfo, product: String) {
        val title = findNodeWithText(root, product)
        if (title == null) {
            fertilizerScrollAttempts++
            announce(product, "$product is not on the current accessibility page. Scrolling down (#$fertilizerScrollAttempts)")
            if (!scrollFertilizerShort()) announce("WAITING", "Could not scroll to $product")
            return
        }

        if (!isNodeVisible(title)) {
            fertilizerScrollAttempts++
            announce(product, "$product is below the visible area. Scrolling down (#$fertilizerScrollAttempts)")
            if (!scrollFertilizerShort()) announce("WAITING", "Could not scroll to visible $product controls")
            return
        }

        val group = findProductGroup(root, product)
        if (group == null) {
            announce(product, "Product group is not ready; waiting for quantity controls")
            return
        }

        val qty = findQuantityNumber(group)
        if (qty == null) {
            fertilizerScrollAttempts++
            announce(product, "Quantity/minus controls not visible yet. Short scrolling within fertilizer list (#$fertilizerScrollAttempts)")
            scrollFertilizerShort()
            return
        }

        fertilizerScrollAttempts = 0
        if (qty > 0) {
            val now = System.currentTimeMillis()
            if (now - lastFertilizerActionAt < 90) return

            announce(product, "Quantity = $qty. Pressing − until 0")

            val quantityNode = findQuantityNode(group)
            val minus = findMinus(group) ?: quantityNode?.let { findMinusNearQuantity(group, it) }
            val ok = if (minus != null) {
                announce("TAP", "$product minus control found in accessibility tree")
                clickNode(minus)
            } else {
                tapMinusByQtyRow(group)
            }

            if (ok) {
                lastFertilizerActionAt = now
            } else {
                announce("RETRYING", "$product minus tap could not be dispatched")
            }
            return
        }

        announce(product, "Quantity = 0. Waiting for Delete confirmation")
        val delete = findClickableText(root, "Delete") ?: findNodeContainingText(root, "Delete")
        if (delete != null && clickNode(delete)) {
            when (product.uppercase(Locale.ROOT)) {
                "NPKS" -> npksRemoved = true
                "DAP" -> dapRemoved = true
                "MOP" -> mopRemoved = true
            }
            announce(product, "Delete clicked. Waiting for $product card to disappear")
            lastFertilizerActionAt = System.currentTimeMillis()
            minusAttemptIndex = 0
            fertilizerScrollAttempts = 0
        } else {
            announce("WAITING", "$product is 0 but Delete dialog/button is not ready")
        }
    }

    private fun findProductGroup(root: AccessibilityNodeInfo, product: String): AccessibilityNodeInfo? {
        val title = findNodeWithText(root, product) ?: return null
        var n: AccessibilityNodeInfo? = title
        repeat(14) {
            n?.let { node ->
                if (findQuantityNumber(node) != null && (findMinus(node) != null || hasQtyLabel(node))) return node
            }
            n = n?.parent
        }
        return title
    }

    private fun hasQtyLabel(root: AccessibilityNodeInfo): Boolean =
        findNodeWithText(root, "Qty") != null

    private fun findMinus(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNode(root) {
            val t = it.text?.toString()?.trim().orEmpty()
            val d = it.contentDescription?.toString()?.trim().orEmpty()
            (t == "−" || t == "-" || t == "–" ||
                d.equals("minus", true) || d.contains("decrease", true) ||
                d.contains("remove quantity", true)) && nearestClickable(it) != null
        }
    }

    private fun findMinusNearQuantity(group: AccessibilityNodeInfo, quantity: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val qr = Rect().also { quantity.getBoundsInScreen(it) }
        if (qr.width() <= 0 || qr.height() <= 0) return null
        val candidates = mutableSetOf<AccessibilityNodeInfo>()
        collect(group) { n ->
            if (n.isClickable) candidates.add(n)
            nearestClickable(n)?.let { candidates.add(it) }
        }
        var best: AccessibilityNodeInfo? = null
        var bestScore = Float.MAX_VALUE
        for (c in candidates) {
            val r = Rect().also { c.getBoundsInScreen(it) }
            if (r.width() <= 0 || r.height() <= 0) continue
            val dx = qr.left - r.right
            val dy = kotlin.math.abs(r.centerY() - qr.centerY())
            if (dx >= -12 && dx <= 110 && dy <= kotlin.math.max(45, qr.height() * 2)) {
                val score = kotlin.math.abs(dx.toFloat()) + dy * 2f
                if (score < bestScore) { bestScore = score; best = c }
            }
        }
        return best
    }

    private fun findQtyLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeWithText(root, "Qty")

    private fun findQuantityNode(group: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val qtyLabel = findQtyLabel(group) ?: return null
        val labelBounds = Rect().also { qtyLabel.getBoundsInScreen(it) }
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        collect(group) { n ->
            val value = n.text?.toString()?.trim()?.toIntOrNull()
            if (value != null && value in 0..100) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.top >= labelBounds.top - 80 && r.top <= labelBounds.bottom + 120 &&
                    r.left >= labelBounds.right) {
                    candidates.add(n to kotlin.math.abs(r.centerY() - labelBounds.centerY()))
                }
            }
        }
        return candidates.minByOrNull { it.second }?.first
    }

    private fun findQuantityNumber(group: AccessibilityNodeInfo): Int? {
        val node = findQuantityNode(group)
        if (node != null) return node.text?.toString()?.trim()?.toIntOrNull()

        val nums = mutableListOf<Int>()
        collect(group) { n ->
            n.text?.toString()?.trim()?.toIntOrNull()?.let {
                if (it in 0..100) nums.add(it)
            }
        }
        return nums.firstOrNull()
    }

    private fun tapMinusByQtyRow(group: AccessibilityNodeInfo): Boolean {
        val quantityNode = findQuantityNode(group) ?: return false
        val qr = Rect().also { quantityNode.getBoundsInScreen(it) }
        if (qr.width() <= 0 || qr.height() <= 0) return false
        val density = resources.displayMetrics.density
        // On the FFS quantity row the minus control is immediately left of the
        // numeric quantity. Try the most likely center first, then small offsets.
        val offsetsDp = floatArrayOf(16f, 20f, 24f, 28f, 32f)
        val offset = offsetsDp[minusAttemptIndex % offsetsDp.size] * density
        minusAttemptIndex++
        val x = (qr.left - offset).coerceAtLeast(2f)
        val y = qr.centerY().toFloat()
        announce("TAP", "Minus accessibility node unavailable; tapping Qty-row minus x=${x.toInt()}, y=${y.toInt()}")
        return dispatchTap(x, y)
    }

    private fun clickProductOption(option: AccessibilityNodeInfo): Boolean {
        // The Neem option is already visible in the screenshot. Prefer the actual
        // clickable row first, then use a screen tap on the visible row. Never close
        // or reopen the dropdown and never dismiss the keyboard.
        val clickable = nearestClickable(option)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis()
            announce("UREA", "Clicked the visible Neem Coated Urea option row")
            return true
        }

        val optionRect = Rect().also { option.getBoundsInScreen(it) }
        val rowRect = Rect(optionRect)
        if (clickable != null) {
            val parentRect = Rect().also { clickable.getBoundsInScreen(it) }
            if (parentRect.width() > 0 && parentRect.height() > 0) rowRect.union(parentRect)
        }

        if (rowRect.width() > 0 && rowRect.height() > 0 && isNodeVisible(option)) {
            val x = rowRect.centerX().toFloat()
            val y = rowRect.centerY().toFloat()
            announce("UREA", "Tapping visible Neem Coated Urea row at x=${x.toInt()}, y=${y.toInt()}")
            if (dispatchTap(x, y)) return true
        }

        if (option.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis()
            announce("UREA", "Clicked Neem Coated Urea option node")
            return true
        }
        return false
    }

    private fun findUreaPickerScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        collect(root) { n ->
            if (!n.isScrollable || !isNodeVisible(n)) return@collect
            val text = collectTexts(n).joinToString(" ").lowercase(Locale.ROOT)
            var score = 0
            if (text.contains("select product")) score += 4
            if (text.contains("urea")) score += 3
            if (text.contains("dap") || text.contains("mop") || text.contains("npks")) score += 1
            if (score > bestScore) { bestScore = score; best = n }
        }
        return best ?: findVisibleScrollable(root)
    }

    private fun findUreaOption(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        collect(root) { n ->
            if (!isNodeVisible(n)) return@collect
            val own = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                .replace(Regex("\\s+"), " ").trim()
            val normalized = own.replace("(", " ").replace(")", " ")
                .replace(Regex("\\s+"), " ").trim()
            val isNeem = normalized.contains("Neem Coated Urea", true)
            val is45Kg = normalized.contains("45 Kg", true) || normalized.contains("45Kg", true)
            if (!isNeem || !is45Kg) return@collect

            val r = Rect().also { n.getBoundsInScreen(it) }
            var score = 0
            // Prefer the actual option row text, not a parent whose subtree merely
            // contains the option. Smaller bounds are much more likely to be the row.
            if (own.contains("Neem Coated Urea", true)) score += 100
            if (own.contains("45 Kg", true) || own.contains("45Kg", true)) score += 50
            if (n.isClickable) score += 40
            if (nearestClickable(n) != null) score += 30
            score -= ((r.width() * r.height()) / 100000).coerceAtMost(50)
            if (score > bestScore) {
                best = n
                bestScore = score
            }
        }
        return best
    }

    private fun hasSelectedUreaProduct(root: AccessibilityNodeInfo): Boolean {
        // Verify Neem in the original Select Product field bounds. This prevents
        // the visible popup option itself from being mistaken for a selected value.
        val field = ureaSelectorBounds ?: return false
        var selected = false
        collect(root) { n ->
            if (selected || !isNodeVisible(n)) return@collect
            val own = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                .replace(Regex("\\s+"), " ").trim()
            if (!own.contains("Neem Coated Urea", true) ||
                !(own.contains("45 Kg", true) || own.contains("45Kg", true) || own.contains("45 KG", true))) return@collect
            val r = Rect().also { n.getBoundsInScreen(it) }
            val cx = r.centerX()
            val cy = r.centerY()
            if (field.contains(cx, cy)) selected = true
        }
        return selected
    }

    private fun tapVisibleNeemByDropdownGeometry(root: AccessibilityNodeInfo): Boolean {
        val selector = findNodeWithText(root, "Select Product")
            ?: findNodeContainingText(root, "Select Product")
            ?: return false
        val r = Rect().also { selector.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0 || !isNodeVisible(selector)) return false

        // The screenshot shows two equal-height popup rows immediately below the
        // selector. Neem is the second row. Use the selector height as the row
        // height so this works across screen densities/resolutions.
        val rowHeight = r.height().toFloat()
        val x = r.centerX().toFloat()
        val y = r.bottom + (rowHeight * 1.5f)
        val dm = resources.displayMetrics
        if (y >= dm.heightPixels - 80f) return false

        announce("UREA", "Tapping visible Neem row at x=${x.toInt()}, y=${y.toInt()} without reopening dropdown")
        return dispatchTap(x, y)
    }

    private fun monitorRetailer(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val configured = prefs.getLong("poll_ms", 100L).coerceIn(80L, 30000L)
        if (now - lastRetailerCheckAt < configured) return
        lastRetailerCheckAt = now

        retailerSearchValue = prefs.getString("retailer_location", retailerSearchValue)
            ?.trim().orEmpty().ifEmpty { retailerSearchValue.ifEmpty { "Korlagudem" } }

        val search = findRetailerSearchField(root)
        val searchButton = findClickableText(root, "Search") ?: findNodeWithText(root, "Search")

        if (!retailerSelected) {
            // Always use the user's configured search text. Do not assume that the
            // initial retailer list is the requested stock/location.
            if (search != null) {
                val editable = if (search.isEditable) search else findFirstEditable(search)
                if (editable != null) {
                    val current = editable.text?.toString()?.trim().orEmpty()
                    if (!current.equals(retailerSearchValue, true)) {
                        if (setEditableText(editable, retailerSearchValue)) {
                            retailerSearchSubmitted = false
                            announce("RETAILER", "Entered retailer/PACS search: $retailerSearchValue")
                            lastActionAt = System.currentTimeMillis()
                            return
                        }
                    }
                }
            }

            // Always submit the user's query at least once before accepting a
            // retailer from the initial/default list.
            if (!retailerSearchSubmitted) {
                if (searchButton != null && clickNode(searchButton)) {
                    retailerSearchSubmitted = true
                    announce("RETAILER", "Searching for $retailerSearchValue")
                    return
                }
                announce("WAITING", "Search button is not ready for $retailerSearchValue")
                return
            }

            val retailer = findRetailerForQuery(root, retailerSearchValue)
            if (retailer != null) {
                if (clickNode(retailer)) {
                    retailerSelected = true
                    retailerUreaSelected = false
                    announce("RETAILER", "Found and selected retailer/PACS matching $retailerSearchValue")
                    return
                }
                announce("WAITING", "Matching retailer/PACS is visible but not clickable yet")
                return
            }

            // The requested retailer is not listed. Immediately click Search again while the
            // same query remains in the field. There is intentionally no retry limit: keep
            // refreshing the retailer results until the requested retailer/PACS appears.
            if (searchButton != null && clickNode(searchButton)) {
                retailerSearchSubmitted = true
                announce("RETAILER", "$retailerSearchValue not listed. Clicking Search again")
                return
            }

            announce("WAITING", "Waiting for Search button or retailer/PACS matching $retailerSearchValue")
            return
        }

        // A retailer card may contain a separate product-group control. The required
        // flow is to select Urea from that card before pressing Next.
        val ureaGroup = findUreaProductGroupControl(root)
        if (!retailerUreaSelected && ureaGroup != null) {
            if (clickNode(ureaGroup)) {
                retailerUreaSelected = true
                announce("RETAILER", "Selected Urea in Select Product Group")
                return
            }
            announce("WAITING", "Urea product group is visible but not clickable yet")
            return
        }

        if (clickText(root, "Next")) {
            currentState = State.RECEIVER
            receiverHandled = false
            retailerUreaSelected = false
            announce("RECEIVER", "Retailer/PACS and Urea selected. Clicked Next")
        } else {
            announce("WAITING", "Retailer/PACS selected. Waiting for Next")
        }
    }

    private fun findRetailerSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result != null || !n.isEditable || !isNodeVisible(n)) return@collect
            val v = (n.hintText?.toString().orEmpty() + " " +
                n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
            if (v.contains("search", true) || v.contains("village", true) || v.contains("centre", true) || v.contains("center", true)) result = n
        }
        return result ?: findNodeContainingText(root, "Search Village or Centre")
    }

    private fun normalizeRetailerQuery(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("\\bpacs\\b"), " ")
            .replace(Regex("\\b(retailer|retail|facility|centre|center)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun findRetailerForQuery(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = normalizeRetailerQuery(query)
        if (q.isEmpty()) return null
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        collect(root) { n ->
            if (!isNodeVisible(n)) return@collect
            val own = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                .replace(Regex("\\s+"), " ").trim()
            if (own.isBlank() || own.equals("Search", true) || own.equals("Select Retailer/PACS Facility", true)) return@collect
            val normalized = normalizeRetailerQuery(own)
            if (normalized.isBlank()) return@collect

            // User input is a location/name fragment, not necessarily the exact
            // retailer label. Example: "Korlagudem" must match "PACS Korlagudem".
            val matches = normalized == q || normalized.contains(q) || terms.all { normalized.contains(it) }
            val clickable = nearestClickable(n)
            if (!matches || clickable == null) return@collect

            var score = 0
            if (normalized == q) score += 1000
            if (normalized.startsWith(q)) score += 300
            if (normalized.contains(q)) score += 200
            score += terms.count { normalized.contains(it) } * 50
            // Prefer the smallest visible text node containing the requested
            // location so a large parent card is not chosen over its actual row.
            val r = Rect().also { n.getBoundsInScreen(it) }
            score -= ((r.width() * r.height()) / 100000).coerceAtMost(100)

            if (score > bestScore) {
                bestScore = score
                best = clickable
            }
        }
        return best
    }

    private fun findUreaProductGroupControl(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = findNodeContainingText(root, "Select Product Group") ?: return null
        var result: AccessibilityNodeInfo? = null
        collect(label) { n ->
            if (result == null && n.text?.toString()?.trim()?.equals("Urea", true) == true && isNodeVisible(n)) {
                result = n
            }
        }
        return result ?: findClickableContaining(root, "Urea")
    }

    private fun isNodeCheckedOrSelected(node: AccessibilityNodeInfo): Boolean =
        node.isChecked || node.isSelected || node.contentDescription?.toString()?.contains("selected", true) == true

    private fun monitorReceiver(root: AccessibilityNodeInfo) {
        receiverMobileValue = prefs.getString("receiver_mobile", receiverMobileValue)?.trim().orEmpty()
        if (receiverMobileValue.isEmpty()) {
            announce("WAITING", "Receiver mobile number is not configured in Agent UI")
            return
        }

        val farmer = findNodeContainingText(root, "Farmer himself/herself")
        if (farmer != null) {
            val selected = farmer.isChecked || farmer.isSelected ||
                farmer.contentDescription?.toString()?.contains("selected", true) == true
            if (!selected && clickNode(farmer)) {
                announce("RECEIVER", "Selected Farmer himself/herself")
                return
            }
        }

        val mobile = findReceiverMobileField(root)
        if (mobile != null) {
            val current = mobile.text?.toString()?.trim().orEmpty()
            if (current != receiverMobileValue) {
                if (setEditableText(mobile, receiverMobileValue)) {
                    announce("RECEIVER", "Entered configured receiver mobile number")
                    return
                }
            }
        } else {
            announce("WAITING", "Receiver mobile number field is not ready")
            return
        }

        val declaration = findDeclarationCheckbox(root)
        if (declaration != null && !declaration.isChecked) {
            if (clickNode(declaration)) {
                announce("RECEIVER", "Checked I hereby declare")
                return
            }
            announce("WAITING", "Declaration checkbox is visible but not clickable yet")
            return
        }

        if (declaration == null) {
            announce("WAITING", "Declaration checkbox is not ready")
            return
        }

        if (clickText(root, "Submit")) {
            currentState = State.SUBMITTING
            receiverHandled = true
            announce("SUBMITTING", "Receiver details completed. Clicked Submit")
        } else {
            announce("WAITING", "Receiver details complete. Waiting for Submit")
        }
    }

    private fun findReceiverMobileField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result != null || !n.isEditable || !isNodeVisible(n)) return@collect
            val v = (n.hintText?.toString().orEmpty() + " " + n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
            if (v.contains("mobile", true) || v.contains("communication", true)) result = n
        }
        return result
    }

    private fun findDeclarationCheckbox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result != null || !n.isCheckable || !isNodeVisible(n)) return@collect
            val parentText = n.parent?.let { collectTexts(it).joinToString(" ") }.orEmpty()
            val own = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
            if (own.contains("declare", true) || parentText.contains("I hereby declare", true)) result = n
        }
        return result
    }

    private fun looksLikeHome(all: String): Boolean {
        return (all.contains("home") && (all.contains("my farm") || all.contains("history") || all.contains("profile"))) &&
            !all.contains("farm overview") && !all.contains("verified holdings")
    }

    private fun isMyFarmScreen(all: String): Boolean = all.contains("farm overview") && all.contains("verified holdings") && all.contains("select land")
    private fun isMyFarmAfterSelection(all: String): Boolean = all.contains("verified holdings") && all.contains("apply for fertilizers") && !all.contains("survey no.")
    private fun looksLikeLandPicker(all: String): Boolean = all.contains("survey no") || all.contains("select all") || (all.contains("done") && all.contains("land"))

    private fun readVerifiedHoldingCount(root: AccessibilityNodeInfo): Int {
        // Prefer the Verified Holdings section, so "Total Plots" or other numbers
        // elsewhere on My Farm cannot be mistaken for the target count.
        val verified = findNodeContainingText(root, "Verified Holdings")
        if (verified != null) {
            val sectionText = collectTexts(verified).joinToString(" ")
            Regex("\\b(\\d+)\\s*Plots?\\b", RegexOption.IGNORE_CASE)
                .find(sectionText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }

        // Some FFS builds expose "Verified Holdings" and "3 Plots" as siblings.
        val texts = collectTexts(root)
        val hasVerified = texts.any { it.contains("Verified Holdings", true) }
        if (hasVerified) {
            for (text in texts) {
                Regex("^\\s*(\\d+)\\s*Plots?\\s*$", RegexOption.IGNORE_CASE)
                    .matchEntire(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun selectVisibleLandEntry(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collect(root) { n ->
            if (!n.isCheckable || n.isChecked || !isNodeVisible(n)) return@collect
            val label = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim()
            if (label.contains("select all", true)) return@collect
            candidates.add(n)
        }

        // Prefer checkboxes associated with a land row. Some builds put the land
        // text on a sibling, so inspect the immediate parent subtree as well.
        fun landish(n: AccessibilityNodeInfo): Boolean {
            var p: AccessibilityNodeInfo? = n
            repeat(3) {
                if (p != null) {
                    val text = collectTexts(p!!).joinToString(" ")
                    if (text.contains("survey", true) || text.contains("plot", true) ||
                        text.contains("land", true) || Regex("\\b\\d{1,3}\\b").containsMatchIn(text)) return true
                    p = p!!.parent
                }
            }
            return false
        }

        val preferred = candidates.firstOrNull { landish(it) } ?: candidates.firstOrNull()
        if (preferred != null && clickNode(preferred)) {
            announce("LANDS", "Selected visible land checkbox")
            return true
        }
        return false
    }

    private fun findVisibleScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result == null && n.isScrollable && isNodeVisible(n)) result = n
        }
        return result
    }

    private fun landPickerContentSignature(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        collect(root) { n ->
            if (n.isCheckable && isNodeVisible(n)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val label = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                    .replace(Regex("\\s+"), " ").trim()
                // Exclude checked/unchecked state. We only want to know whether
                // the visible LAND LIST itself moved.
                if (!label.contains("select all", true)) {
                    parts.add("${r.left},${r.top},${r.right},${r.bottom}:$label")
                }
            }
        }
        return parts.sorted().joinToString("|")
    }



    private fun clickVisibleGfrOption(option: AccessibilityNodeInfo): Boolean {
        val clickable = nearestClickable(option)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis()
            return true
        }
        val r = Rect().also { option.getBoundsInScreen(it) }
        if (r.width() > 0 && r.height() > 0 && isNodeVisible(option)) {
            // Tap the center of the visible option text/row. On the FFS custom
            // dialog this toggles the radio even when the radio is not exposed.
            return dispatchTap(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    private fun clickVisibleGfrContinue(node: AccessibilityNodeInfo): Boolean {
        val clickable = nearestClickable(node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis()
            return true
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() > 0 && r.height() > 0 && isNodeVisible(node)) {
            return dispatchTap(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    private fun clickText(root: AccessibilityNodeInfo, text: String): Boolean = clickNode(findClickableText(root, text) ?: findNodeWithText(root, text))
    private fun findClickableText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result == null && (n.text?.toString()?.trim()?.equals(text, true) == true || n.contentDescription?.toString()?.trim()?.equals(text, true) == true) && nearestClickable(n) != null) result = n
        }
        return result
    }
    private fun findClickableContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            val v = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
            if (result == null && v.contains(text, true) && nearestClickable(n) != null) result = n
        }
        return result
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val target = nearestClickable(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis(); return true
        }
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            val ok = dispatchTap(r.exactCenterX(), r.exactCenterY())
            if (!ok) announce("RETRYING", "Accessibility click failed and gesture fallback was rejected")
            return ok
        }
        return false
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        if (ok) lastActionAt = System.currentTimeMillis()
        return ok
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(8) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(node) { if (result == null && it.isEditable) result = it }
        return result
    }

    private fun setEditableText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }


    private fun scrollLandPicker(root: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (target == null && n.isScrollable && isNodeVisible(n)) target = n
        }
        if (target != null && target!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            lastActionAt = System.currentTimeMillis()
            return true
        }
        return scrollForward(root)
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (target == null && n.isScrollable) target = n
        }
        val ok = (target ?: root).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (ok) {
            lastActionAt = System.currentTimeMillis()
            return true
        }

        // Some FFS screens expose a visual ScrollView but do not expose
        // ACTION_SCROLL_FORWARD. Use one short swipe as a gesture fallback.
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.50f
        val startY = dm.heightPixels * 0.78f
        val endY = dm.heightPixels * 0.34f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 110))
            .build()
        val gestureOk = if (Build.VERSION.SDK_INT >= 24) dispatchGesture(gesture, null, null) else false
        if (gestureOk) lastActionAt = System.currentTimeMillis()
        return gestureOk
    }

    private fun isNodeVisible(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        val dm = resources.displayMetrics
        val top = 24
        val bottom = dm.heightPixels - 24
        return r.bottom > top && r.top < bottom
    }

    private fun findSelectableTextContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { n ->
            if (result == null && n.text?.toString()?.contains(text, true) == true && nearestClickable(n) != null) result = n
        }
        return result
    }

    private fun hasText(root: AccessibilityNodeInfo, text: String) = findNodeWithText(root, text) != null
    private fun findNodeWithText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? = findNode(root) {
        it.text?.toString()?.trim()?.equals(text, true) == true || it.contentDescription?.toString()?.trim()?.equals(text, true) == true
    }
    private fun findNodeContainingText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? = findNode(root) {
        it.text?.toString().orEmpty().contains(text, true) || it.contentDescription?.toString().orEmpty().contains(text, true)
    }
    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        collect(root) { if (result == null && predicate(it)) result = it }
        return result
    }
    private fun collect(root: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        visitor(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { collect(it, visitor) }
    }
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        collect(root) { n ->
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.distinct()
    }
    private fun parseRetailerCount(texts: List<String>): Int {
        val regexes = listOf(
            Regex("total retailers\\s*\\(\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE),
            Regex("total retailers\\s*[:\\-]?\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("retailers?\\s*[:\\-]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        )
        for (text in texts) {
            for (regex in regexes) {
                regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
        }
        if (texts.any { it.contains("no retailers", true) || it.contains("no retailer available", true) }) return 0
        return 0
    }
    private fun containsAny(s: String, vararg values: String) = values.any { s.contains(it) }

    private fun announce(state: String, message: String) {
        val line = "$state — $message"
        if (line == lastAnnounced) return
        lastAnnounced = line
        val old = prefs.getString("agent_log", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(49)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())
        prefs.edit().putString("agent_status", state).putString("agent_log", (old + "$stamp  $line").takeLast(50).joinToString("\n")).apply()
        broadcastStatus(line)
        updateOverlay(state, message)
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt >= 1200L || state == "ERROR" || state == "SUCCESS") {
            lastNotificationAt = now
            showNotification("FFS Agent: $state", message)
        }
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(android.content.Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, currentState.name)
            putExtra(EXTRA_LOG, message)
        })
    }

    private fun stopAutomation(reason: String) {
        requested = false
        currentState = if (reason.contains("successful", true)) State.SUCCESS else State.ERROR
        handler.removeCallbacksAndMessages(null)
        announce(if (currentState == State.SUCCESS) "SUCCESS" else "STOPPED", reason)
        updateOverlay(if (currentState == State.SUCCESS) "SUCCESS" else "STOPPED", reason)
        broadcastStatus("STOPPED — $reason")
        showNotification("FFS Agent stopped", reason)
    }

    private fun schedule() {
        if (!requested) return
        val configuredPoll = prefs.getLong("poll_ms", 100L)
        val ms = if (configuredPoll >= 1000L) 80L else configuredPoll.coerceIn(60L, 30000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ tick() }, ms)
    }

    private fun createOverlay() {
        if (overlay != null) return
        overlayManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(215, 20, 20, 20))
            textSize = 12f
            setPadding(18, 12, 18, 12)
            gravity = Gravity.CENTER_VERTICAL
            text = "FFS Agent\nWaiting..."
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 28
        }
        try { overlayManager?.addView(overlay, params) } catch (_: Exception) { overlay = null }
    }

    private fun updateOverlay(state: String, message: String) {
        handler.post {
            overlay?.text = "FFS Agent  •  $state\n$message"
        }
    }

    private fun removeOverlay() {
        try { overlay?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        overlayManager = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("ffs", "FFS Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "ffs") else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        nm.notify(42, builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setOngoing(requested).setAutoCancel(!requested).build())
    }
}
