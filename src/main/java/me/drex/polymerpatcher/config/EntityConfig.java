package me.drex.polymerpatcher.config;

public class EntityConfig {

    /**
     * Automatically expose mod menus whose complete live slot geometry is already representable by
     * a vanilla 9-wide container. Menus with buttons, fake slots, non-grid cells or unusual player
     * inventories are deliberately rejected and retain the safe "install the mod" message.
     */
    public boolean automaticModdedMenus = true;

    /**
     * Leave a mod's recipes out of the recipe book of a player who has not got that mod.
     * <p>
     * A client decides which recipes it can make by matching its own inventory against the ingredients,
     * and for a player without the mod both of those are stand-ins - so it lights up hundreds of recipes
     * it cannot make. The recipes still work; they are simply not offered to somebody who could not read
     * them anyway. See {@link me.drex.polymerpatcher.item.RecipeBookContents}.
     */
    public boolean hideRecipesForMissingMods = false;

    /**
     * Show how near a piece of armour's ability is to ready, as a bar above the hotbar.
     * <p>
     * A mod that gives armour an ability draws its own gauge for it, and a client without the mod sees
     * nothing at all - the cloak of darkness charges, empties and recharges in silence. The number is
     * the mod's own, read off the item; the bar is a boss bar, because that is the one gauge every
     * client can already draw. See {@link me.drex.polymerpatcher.item.ArmourAbilityMeter}.
     */
    public boolean showArmourAbilityMeter = true;

    /**
     * Leave a modded item out of the sum a client does to decide which recipes it can make.
     * <p>
     * A vanilla recipe asks for a tag rather than a list - two planks, not two oak planks - and mods
     * put their own wood in that tag. A client running Polymer but not the mod is sent those entries,
     * as the stand-ins it is shown everywhere else, so what reaches it is a tag with a handful of
     * stand-ins in it. Its own modded items are the same stand-ins. Carrying any two of them is then
     * carrying two of something every such tag accepts: two lumps of guano lit up every recipe asking
     * for two of anything a mod has joined, and clicking one laid out a grid the server would not
     * fill. The sum was being done on items the client cannot tell apart.
     * <p>
     * With this on, what a client is asked to count is only the part of each ingredient it can
     * recognise. The recipe still shows every item it accepts, modded ones included, and putting them
     * in a grid still crafts - this is only about which entries are lit. The cost is the other way
     * round: an entry that could have been made out of modded wood alone is no longer lit either.
     */
    public boolean onlyCountItemsClientsCanTellApart = true;

    /**
     * Draw a single piece of a model that a renderer draws on its own.
     * <p>
     * A renderer that wants a head and no body reaches past the model and draws one piece of it, which
     * nothing watching whole models can see. Alex's Caves' dinosaur spirits are drawn that way, and so
     * are its boats and its submarine - all of them invisible without this.
     * <p>
     * Off by default: it is the newest thing here, it places a display per piece, and it has not been
     * watched with players connected for long enough to know what it costs.
     */
    public boolean drawModelPiecesDrawnAlone = false;
    /**
     * Whether a mob this mod has no model for is drawn as a named box rather than as nothing at all.
     * <p>
     * On by default because the alternative is an invisible mob that still hits you, and because the
     * name on it is the quickest way to find out which entity is missing.
     */
    public boolean showPlaceholders = true;

    /**
     * Whether a player who already has a mod is shown that mod's own mobs rather than stand-ins.
     * <p>
     * Off by default, and deliberately so. It only works when every one of the four decisions made
     * about a mob - what the registry calls its type, what the spawn packet says it is, which fields
     * its updates may carry, and whether a stand-in is drawn - agrees, and they are made at different
     * moments during joining. They now share one answer per player, decided once, but the cost of that
     * answer being wrong is the player being disconnected rather than something looking odd.
     * <p>
     * Turn it on if you want players who have the mods to see the real mobs, and turn it off again if
     * anyone cannot join.
     */
    public boolean nativeClients = false;

    /**
     * Whether a player who has a mod is handed that mod's items as themselves, rather than as stand-ins.
     * <p>
     * A stand-in keeps a modded item's model and nothing else, so the mod's own code on a modded client -
     * a gauntlet's charge, a shield's slam, a tablet's colour, the spelunkery table's minigame - has
     * nothing it recognises to act on.
     * <p>
     * Sending the real item is only safe if the client numbers items exactly as this server does, and
     * Polymer deliberately keeps modded items out of the registry sync that would make it so. While a
     * client joins, the vanilla known-packs answer says which of this server's mods it has at the same
     * version; the items of those mods, and only those, are put back into a sync sent to that client. See
     * {@link me.drex.polymerpatcher.util.NativeItemSync}.
     * <p>
     * Clients without Fabric API, and the items of any mod a client does not have at this server's version,
     * keep getting stand-ins exactly as before. If a modded player cannot join after this is turned on, turn
     * it off.
     */
    public boolean nativeItems = true;

    /**
     * Whether a client handed real items is also told how this server numbers the item data types of the
     * mods it has, so an item it sends back - a creative-mode pick, above all - is read as what it is.
     * <p>
     * Without it a client numbers those types by itself, in the order its own mods registered them, and
     * this server read a Fancy Portals command stick's command as a portal wand's settings and dropped the
     * player. See {@link me.drex.polymerpatcher.util.ComponentNumbering}. Only takes effect alongside
     * {@link #nativeItems}; if a modded player cannot join after this is turned on, turn it off.
     */
    public boolean nativeComponents = true;

    /**
     * Whether a player whose copy of one of this server's content mods is a different version is
     * disconnected, with a screen naming the version to install.
     * <p>
     * Only mods with content Polymer hides from clients count, and only once the client has shown it has
     * the mod by opening one of its channels - a player without a mod is never refused for it. Turn it off
     * to let mismatched clients in; they get stand-ins for that mod's items.
     */
    public boolean refuseMismatchedMods = true;

    /**
     * Whether a skreecher sets off sculk sensors.
     * <p>
     * This one changes how Alex's Mobs plays rather than repairing anything: the skreecher declares
     * itself as dampening vibrations, the same way wool does, so the game skips every event it makes
     * and no sensor hears it. That is the mod's own decision and it behaves the same without this mod
     * installed. Set it to false to have the skreecher back exactly as Alex's Mobs intends.
     */
    public boolean skreechersTriggerSculk = true;

    /**
     * How near a player has to be for a mob to be posed on every tick, in blocks.
     * <p>
     * Posing a mob means running its real renderer - the model, every feature layer, the lot - on the
     * server thread. That is the most expensive thing this mod does, and it was done twenty times a
     * second for every modded mob any player could see, however far off it was.
     * <p>
     * Beyond this distance a mob is posed less often instead. It still moves, turns and is drawn; the
     * pose it holds is simply refreshed a few times a second rather than twenty, which at forty blocks
     * is not a difference anyone can see. Set it very large to pose everything every tick as before.
     */
    /**
     * Let a player see the parts of their own armour that hang off their back.
     * <p>
     * The wearer is ordinarily sent none of their own armour: the server is never told whether they are in
     * first or third person, so anything drawn on their body would hang in front of their face. A cape and
     * its tails are the exception - they are behind the wearer, where the camera is not - so where a piece
     * is drawn as nothing but those, the wearer is sent it too and can see their own cloak in F5.
     */
    public boolean showYourOwnArmourExtras = true;

    public int fullDetailDistance = 32;

    /**
     * How many ticks between poses for a mob beyond {@link #fullDetailDistance}.
     * <p>
     * Four means five poses a second. Raising it saves more and makes distant animation choppier; one
     * turns the saving off without turning off the check.
     */
    public int distantPoseInterval = 4;

    /**
     * How far a drawn model carries, as a multiple of the distance the game would draw an ordinary
     * display at.
     * <p>
     * Each part of a modded mob is a display of its own - a candicorn is twenty-one of them, a gum worm
     * ten per segment - and a cave full of them is a cave full of entities to draw. Two is generous;
     * one draws them no further than the game would draw anything else, and is the first thing to try
     * where somewhere is heavy.
     */
    public float displayViewRange = 2.0F;

    /**
     * Models made from many display entities are the most expensive ones for a vanilla client. Past
     * this many visible parts they use {@link #complexModelViewRange} rather than the generous general
     * range above. Nearby models are unchanged; this only stops a many-part mob behind half a cave
     * from remaining in the client's render list.
     */
    public int complexModelPartThreshold = 24;

    /** Draw-distance multiplier used by models at or above {@link #complexModelPartThreshold}. */
    public float complexModelViewRange = 1.25F;

    /**
     * Furthest distance at which server-replayed compatibility particles are sent to a player.
     * Client-only mod effects become individual network packets when replayed for vanilla clients,
     * so sending ambience they cannot meaningfully see is pure client and network cost.
     */
    public float compatibilityParticleDistance = 48.0F;

    /** Maximum compatibility-particle packets one player receives in one server tick. */
    public int compatibilityParticlePacketsPerTick = 48;
}
