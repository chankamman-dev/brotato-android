extends "res://singletons/crash_reporter.gd"

# Android port, external game files: the APK carries no game content. Brotato.pck (downloaded from
# Steam, or the player's own copy) is the main pack, and the launcher writes an override.cfg next to
# it that makes this script the CrashReporter autoload -- the first autoload -- so the port's changes
# are in place before any other game script loads:
# - user://game/overlay.zip (copied from the APK by the launcher) is mounted over the game with
#   replace_files: the Android Steam platform (singletons/platforms/steam.gd) and its remap;
# - the mod loader's Steam profile looks for Workshop items in user://workshop. The profile was
#   already loaded by crash_reporter.gd's preload, so the cached resource is changed in place.
# Everything else is the game's own crash reporter (its _init runs first).

const ANDROID_OVERLAY := "user://game/overlay.zip"
const ANDROID_WORKSHOP := "user://workshop"

var _android_steam_profile: Resource


func _ready() -> void:
	# A routine launch starts the game while the launcher's Steam sync is still running. All the
	# autoloads are instanced before any of them gets _ready (that is most of the loading time), and
	# this one comes first, so ProgressData reads the saves only after the wait below: the cloud
	# saves are in place by then, and the slow part of loading overlapped the sync.
	if Engine.has_singleton("Steam"):
		var t0 := OS.get_ticks_msec()
		var done = Engine.get_singleton("Steam").waitForSaves(12000)
		print("android boot: saves ", "ready" if done else "not synced (offline?)", " after ", OS.get_ticks_msec() - t0, " ms")


func _init() -> void:
	if ProjectSettings.load_resource_pack(ANDROID_OVERLAY, true):
		print("android boot: overlay mounted")
	else:
		printerr("android boot: could not mount ", ANDROID_OVERLAY)
	_android_steam_profile = load("res://addons/mod_loader/options/profiles/steam.tres")
	if _android_steam_profile:
		_android_steam_profile.override_path_to_workshop = ANDROID_WORKSHOP
