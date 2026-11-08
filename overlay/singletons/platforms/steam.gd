class_name SteamPlatform
extends AbstractPlatform

# Android port of the PC SteamPlatform (decompiled from steam.gdc, game 1.2.0.1).
# On PC "Steam" is GodotSteam compiled into the engine. On Android it is the Java plugin
# dev.kmbrotato.steam.SteamPlugin, which answers from cached state (sign-in, ownership, achievements)
# and does the network work on its own threads: the launcher signs in and pulls the Steam Cloud saves
# before the game starts, a background session reports playtime and unlocks achievements, and
# finished save writes are uploaded.
# Differences from the PC script:
# - Not signed in (or no plugin): play offline. Saves go to user://user, no achievements, and DLC
#   counts as NOT owned (the PC script treats a failed Steam init as owning every DLC).
# - DLC ownership is what Steam reported at the last launch sync (cached, so it works offline).
# - Steam stats are never reset from here; store and workshop pages open in the browser.
# - The SteamID64 crosses JNI as a string: Godot 3 passes plugin ints as 32-bit.
# Release templates skip GDScript's null-call checks, so nothing here may call into a null `steam`.

var steam
var quit_game: = false
var init_error: = false
var steam_id: = 0

var steam_languages_map = {
	"english": "en",
	"french": "fr",
	"schinese": "zh",
	"tchinese": "zh_TW",
	"japanese": "ja",
	"koreana": "ko",
	"russian": "ru",
	"polish": "pl",
	"spanish": "es",
	"brazilian": "pt",
	"german": "de",
	"turkish": "tr",
	"italian": "it"
}

var steam_app_id_mapping: = {
	"base": 1942280,
	"abyssal_terrors": 2868390,
	"primal_dread": 4498220,
}

var dlc_store_urls: = {
	"abyssal_terrors": "https://store.steampowered.com/app/2868390",
	"primal_dread": "https://store.steampowered.com/app/4498220",
	"bundle": "https://store.steampowered.com/bundle/94275/Brotato__Abyssal_Terrors_DLC__Primal_Dread_DLC/",
}


func _init() -> void:
	if Engine.has_singleton("Steam"):
		steam = Engine.get_singleton("Steam")
	if steam == null:
		print("Steam plugin missing: playing offline")
		init_error = true
		return
	if not steam.isSignedIn():
		print("Steam: not signed in, playing offline (saves in user://user)")
		init_error = true
		return
	if not steam.isSubscribed():
		printerr("Not owned on Steam: Shutting down")
		quit_game = true
		return
	steam_id = _parse_u64(steam.getSteamIDString())
	prints("steam_id:", steam_id)
	prints("steam_is_online:", steam.loggedOn())
	for dlc in ["abyssal_terrors", "primal_dread"]:
		prints("steam_dlc:", dlc, steam.isDLCOwned(steam_app_id_mapping[dlc]))


func _parse_u64(s: String) -> int:
	var v: = 0
	for i in s.length():
		var d = s.ord_at(i) - 48
		if d < 0 or d > 9:
			return 0
		v = v * 10 + d
	return v


func _enter_tree():
	if quit_game:
		get_tree().notification(MainLoop.NOTIFICATION_WM_QUIT_REQUEST)


func get_type() -> int:
	return PlatformType.STEAM


func get_user_id() -> String:
	if steam_id != 0:
		return str(steam_id)
	else:
		return "user"


func get_language() -> String:
	if steam != null:
		var steam_lang = steam.getGameLanguage()
		if steam_languages_map.has(steam_lang):
			return steam_languages_map.get(steam_lang)
		return "en"
	var locale: String = OS.get_locale()
	if locale.begins_with("zh"):
		if locale.find("TW") != -1 or locale.find("HK") != -1 or locale.find("Hant") != -1:
			return "zh_TW"
		return "zh"
	var short = locale.substr(0, 2)
	if steam_languages_map.values().has(short):
		return short
	return "en"


func is_dlc_owned(dlc_my_id: String) -> bool:
	if steam == null:
		return false
	if not steam_app_id_mapping.has(dlc_my_id):
		print("DLC installed check for %s failed" % dlc_my_id)
		return false
	return steam.isDLCOwned(steam_app_id_mapping[dlc_my_id])


func is_challenge_completed(chal_id: String) -> bool:
	if steam_id == 0:
		return false
	return steam.isAchieved(chal_id)


func complete_challenge(chal_id: int) -> void:
	if steam_id == 0:
		return
	var name = Keys.hash_to_string[chal_id]
	if not steam.isAchieved(name):
		var _queued = steam.setAchievement(name)
		# The Steam overlay's "achievement unlocked" notice, for a challenge completed while
		# playing. ChallengeService re-reports every completed challenge at start-up, from the
		# autoloads' _ready, before the first frame: those get no notice.
		if Engine.get_idle_frames() > 0:
			var title := ""
			var desc := ""
			var chal = ChallengeService.get_chal(chal_id)
			if chal != null:
				title = chal.get_name_text()
				desc = chal.get_description_text()
			steam.notifyAchievement(name, title, desc, TranslationServer.get_locale())


func reinitialize_store_data() -> void:
	print("steam reset data: not done on Android (Steam achievements are kept)")


func _open(url: String) -> void:
	if steam != null:
		steam.openUrl(url)
	else:
		var _error: = OS.shell_open(url)


func open_store_page(url: String) -> void:
	_open(url)


func open_mods_page() -> void:
	_open("https://steamcommunity.com/app/1942280/workshop/")


func get_dlc_url(dlc_id: String = "abyssal_terrors") -> String:
	return dlc_store_urls.get(dlc_id, dlc_store_urls["abyssal_terrors"])


func get_more_games_url() -> String:
	return "https://www.blobfishgames.com/games"


# Called by the mod loader (addons/mod_loader/internal/third_party/steam.gd) while it starts: it
# loads the zips of user://workshop/<id>/ for these ids (overlay steam.tres points it there).
# The launcher downloads the subscribed items into that folder before the game starts.
func get_subscribed_mods() -> Array:
	var ids: = []
	if steam == null or init_error:
		return ids
	for s in steam.getSubscribedItemsString().split(",", false):
		var v: = _parse_u64(s)
		if v != 0:
			ids.append(v)
	prints("steam_workshop_items:", ids)
	return ids
