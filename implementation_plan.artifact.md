# Fix EPG Performance, Stability, and Channel Naming

This plan addresses performance issues and crashes in the EPG Grid (Timeline), and reverts channel name cleaning as requested by the user.

## User Review Required

> [!IMPORTANT]
> Channel names will no longer be "cleaned" (removing prefixes like "SE:" or suffixes like "HD"). They will appear exactly as they are provided by the IPTV server. This may affect how picons (icons) are matched if they relied on the cleaned names.

## Proposed Changes

### [Component] Media Repository

#### [MODIFY] [MediaRepository.kt](file:///C:/Users/marcu/AndroidStudioProjects/MMTV/app/src/main/java/com/example/mmtv/repository/MediaRepository.kt)
- Update `syncLiveChannels` to use the raw stream name instead of the cleaned name.
- Keep the `cleanChannelName` function for internal search/matching if needed, but stop using it for the primary display title.

### [Component] Media ViewModel

#### [MODIFY] [MediaViewModel.kt](file:///C:/Users/marcu/AndroidStudioProjects/MMTV/app/src/main/java/com/example/mmtv/ui/MediaViewModel.kt)
- Fix `getFullEpgForId` to avoid launching coroutines directly during composition.
- Improve EPG caching and batching to reduce database load.

### [Component] UI Components

#### [MODIFY] [EpgGrid.kt](file:///C:/Users/marcu/AndroidStudioProjects/MMTV/app/src/main/java/com/example/mmtv/ui/components/EpgGrid.kt)
- Use `LaunchedEffect` for fetching EPG data instead of triggering it during composition.
- Add safety checks to horizontal scroll animations to prevent crashes during rapid navigation.
- Optimize row rendering to improve performance on TV devices.

## Verification Plan

### Automated Tests
- N/A (Manual verification on TV UI is more effective for performance and focus issues).

### Manual Verification
1.  **Channel Names**: Verify that live channels now show their full names (e.g., "SE: SVT1 HD" instead of "SVT1").
2.  **EPG Grid Performance**: Open the EPG Grid and navigate rapidly Up/Down and Left/Right. Ensure it doesn't crash and remains responsive.
3.  **Swedish EPG**: Verify that Swedish channels show EPG data correctly.
