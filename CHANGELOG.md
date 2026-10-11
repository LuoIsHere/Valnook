# Changelog

## 0.0.15

- Reorganized web account details into separate translucent subaccount cards, with properly wrapped notes and a single account editing entry point.
- Fixed subaccount name editing and separated existing subaccounts from the add-subaccount form. Newly created subaccounts appear at the end of the list.
- Set the LAN web management port to `8765`, with a clear error when the port is unavailable.
- Added softer transitions for web cards, charts, distribution bars, navigation, side panels, expandable sections, and notifications, with support for reduced motion.
- Reduced loading flicker, preserved existing content during refresh, and prevented outdated responses from replacing the active view.
- Made web side panels resizable by dragging their edge, with a shared width across panels during the page session.
- Made account editing return to the parent account panel after saving, cancelling, or closing. Deposit, holding, and trade panels now follow the same navigation hierarchy and preserve the parent scroll position.
