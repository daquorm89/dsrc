package script.library;

import script.*;

import java.util.Vector;

public class space_transition extends script.base_script
{
    public space_transition()
    {
    }
    public static final boolean debugSpaceTransition = true;
    public static final String DATATABLE_SHIP_START_LOCATIONS = "datatables/ship/ship_start_locations.iff";
    public static final String COLUMN_TEMPLATE = "template";
    public static final String COLUMN_SLOT = "slot";
    public static final String COLUMN_CELL = "cell";
    public static final String COLUMN_X = "x";
    public static final String COLUMN_Y = "y";
    public static final String COLUMN_Z = "z";
    public static final int NEWBIE_CONVO_DELAY = 60;
    public static final int SHIP_LIMIT_EXPANSION = 6;
    public static final int SHIP_LIMIT_MAX = 3;
    public static final float STATION_COMM_MAX_DISTANCE = 750.0f;
    public static final String POB_SHIP_PILOT_SLOT_NAME = "ship_pilot_pob";
    public static final String SHIP_PILOT_SLOT_NAME = "ship_pilot";
    public static final string_id SID_PVP_NOW_OVERT = new string_id("space/space_interaction", "pvp_now_overt");
    public static final string_id SID_PVP_NOW_NEUTRAL = new string_id("space/space_interaction", "pvp_now_neutral");
    public static void handlePotentialSceneChange(obj_id player) throws InterruptedException
    {
        if (utils.hasLocalVar(player, "loggingOut"))
        {
            return;
        }
        obj_id containingShip = getContainingShip(player);
        LIVE_LOG("TeleportFixup", "containingShip is " + containingShip);
        if (isSpaceScene())
        {
            if (isIdValid(containingShip))
            {
                adjustShipTeleportFixupInSpaceScene(containingShip);
                if (getOwner(containingShip) == player)
                {
                    updateShipFaction(containingShip, player);
                }
                doAIImmunityCheck(containingShip);
                return;
            }
            obj_id launchedShip = getObjIdObjVar(player, "space.launch.ship");
            int launchedShipStartIndex = getIntObjVar(player, "space.launch.startIndex") - 1;
            if ((!isGod(player) || (!utils.checkConfigFlag("ScriptFlags", "e3Demo")) && shouldSendToGroundOnLogout()))
            {
                removeObjVar(player, "space.launch.ship");
                removeObjVar(player, "space.launch.startIndex");
            }
            if (isIdValid(launchedShip) && launchedShip.isLoaded())
            {
                LOG("space", "checking " + launchedShip);
                obj_id shipContainer = getContainedBy(launchedShip);
                LOG("space", "2 shipContainer is " + shipContainer);
                if (!isIdValid(shipContainer))
                {
                    LOG("space", "shipContainer is " + shipContainer);
                    Vector startLocations = getShipStartLocations(launchedShip);
                    if (startLocations != null && startLocations.size() > 0)
                    {
                        if (launchedShipStartIndex < 0)
                        {
                            launchedShipStartIndex = 0;
                        }
                        location loc = ((location)startLocations.get(launchedShipStartIndex % startLocations.size()));
                        if (loc.cell == null)
                        {
                            boolean success = equip(player, launchedShip, loc.area);
                            if (success)
                            {
                                if (debugSpaceTransition)
                                {
                                    LOG("space_transition", "Player [" + player + "] in space, going to an already unpacked ship [" + launchedShip + "] in station [" + loc.area + "] succeeded.");
                                }
                                return;
                            }
                            else if (debugSpaceTransition)
                            {
                                LOG("space_transition", "Player [" + player + "] in space, going to an already unpacked ship [" + launchedShip + "] in station [" + loc.area + "] failed.");
                            }
                        }
                        else
                        {
                            if (debugSpaceTransition)
                            {
                                LOG("space_transition", "Player [" + player + "] in space, going to an already unpacked ship [" + launchedShip + "] at location [" + loc + "].");
                            }
                            setLocation(player, loc);
                            return;
                        }
                    }
                    else if (hasObjVar(player, space_dungeon.VAR_TICKET_DUNGEON) && space_dungeon.isSpaceDungeon(launchedShip) && space_dungeon.getDungeonIdForPlayer(player) == launchedShip)
                    {
                        String dungeon = getStringObjVar(player, space_dungeon.VAR_TICKET_DUNGEON);
                        String cellName = space_dungeon_data.getDungeonStartCellName(dungeon);
                        location start = space_dungeon_data.getDungeonStartLocation(dungeon);
                        obj_id cell = getCellId(launchedShip, cellName);
                        start.cell = cell;
                        LOG("space_transition", "Sending player " + player + " to dungeon " + dungeon + " " + launchedShip);
                        setLocation(player, start);
                        return;
                    }
                    else if (debugSpaceTransition)
                    {
                        LOG("space_transition", "Player [" + player + "] in space, going to an already unpacked ship [" + launchedShip + "] but could not find a start location.");
                    }
                }
                else
                {
                    if (getOwner(launchedShip) == player && getContainedBy(getContainedBy(launchedShip)) == utils.getDatapad(player))
                    {
                        if (debugSpaceTransition)
                        {
                            LOG("space_transition", "Player [" + player + "] in space, going to his own packed ship [" + launchedShip + "].");
                        }
                        if (unpackShipForPlayer(player, launchedShip))
                        {
                            return;
                        }
                    }
                }
            }
            if (debugSpaceTransition)
            {
                if (isIdValid(launchedShip))
                {
                    LOG("space_transition", "Player " + player + " could not enter ship in space, sending to launch point. Ship(" + launchedShip + ") isValid(" + isIdValid(launchedShip) + ") isLoaded(" + launchedShip.isLoaded() + ")");
                }
                else
                {
                    LOG("space_transition", "Player " + player + " could not enter ship in space, sending to launch point. Ship(null)");
                }
                String scripts = "";
                String[] scriptArray = getScriptList(player);
                for (String s : scriptArray) {
                    scripts += (s + "|");
                }
                LOG("space_transition", "Detailed player info: objvars: " + getPackedObjvars(player) + " scripts: " + scripts);
            }
            teleportPlayerToLaunchLoc(player);
        }
        else
        {
            removeObjVar(player, "space.launch");
            if (isIdValid(containingShip))
            {
                if (debugSpaceTransition)
                {
                    LIVE_LOG("TeleportFixup", "Player " + player + " in a ship but not in space, packing the ship.");
                }
                packShip(containingShip);
            }
        }
    }
    public static void handleLogout(obj_id player) throws InterruptedException
    {
        obj_id containingShip = getContainingShip(player);
        if (isIdValid(containingShip))
        {
            if (debugSpaceTransition)
            {
                LOG("space_transition", "player " + player + " logging out in ship " + containingShip);
            }
            obj_id owner = getOwner(containingShip);
            if (owner == player)
            {
                utils.setLocalVar(player, "loggingOut", true);
                packShip(containingShip);
                utils.removeLocalVar(player, "loggingOut");
            }
            if (!isGod(player))
            {
                if (player == getPilotId(containingShip))
                {
                    unpilotShip(player);
                }
                if (player != owner || shouldSendToGroundOnLogout())
                {
                    teleportPlayerToLaunchLoc(player);
                }
            }
        }
    }
    public static boolean shouldSendToGroundOnLogout() throws InterruptedException
    {
        String s = getConfigSetting("ScriptFlags", "sendToGroundOnLogout");
        if (s != null && (s.equals("false") || s.equals("0") || s.equals("off")))
        {
            return false;
        }
        return true;
    }
    public static void setLaunchInfo(obj_id player, obj_id ship, int startLocationIndex, location groundLoc) throws InterruptedException
    {
        setObjVar(player, "space.launch.worldLoc", groundLoc);
        setObjVar(player, "space.launch.ship", ship);
        setObjVar(player, "space.launch.startIndex", startLocationIndex);
    }
    public static obj_id getContainingShip(obj_id obj) throws InterruptedException
    {
        obj_id containedBy = getContainedBy(obj);
        while (isIdValid(containedBy))
        {
            if (isGameObjectTypeOf(getGameObjectType(containedBy), GOT_ship))
            {
                return containedBy;
            }
            containedBy = getContainedBy(containedBy);
        }
        return null;
    }
    public static obj_id[] findShipControlDevicesForPlayer(obj_id player) throws InterruptedException
    {
        return findShipControlDevicesForPlayer(player, false);
    }
    public static obj_id[] findShipControlDevicesForPlayer(obj_id player, boolean checkHangar) throws InterruptedException
    {
        obj_id datapad = utils.getDatapad(player);
        obj_id playerHangar = obj_id.NULL_ID;
        if (checkHangar)
        {
            playerHangar = utils.getPlayerHangar(player);
        }
        if (isIdValid(datapad))
        {
            obj_id[] datapadContents = getContents(datapad);
            if (datapadContents != null)
            {
                int count = 0;
                for (obj_id datapadContent1 : datapadContents) {
                    if (isIdValid(datapadContent1) && getGameObjectType(datapadContent1) == GOT_data_ship_control_device) {
                        ++count;
                    }
                }
                if (checkHangar && isIdValid(playerHangar))
                {
                    obj_id[] hangarContents = getContents(playerHangar);
                    if (hangarContents != null && hangarContents.length > 0)
                    {
                        for (obj_id hangarContent : hangarContents) {
                            if (isIdValid(hangarContent) && getGameObjectType(hangarContent) == GOT_data_ship_control_device) {
                                ++count;
                            }
                        }
                    }
                }
                if (count > 0)
                {
                    obj_id[] shipControlDevices = new obj_id[count];
                    count = 0;
                    for (obj_id datapadContent : datapadContents) {
                        if (isIdValid(datapadContent) && getGameObjectType(datapadContent) == GOT_data_ship_control_device) {
                            shipControlDevices[count++] = datapadContent;
                        }
                    }
                    if (checkHangar && isIdValid(playerHangar))
                    {
                        obj_id[] hangarContents = getContents(playerHangar);
                        if (hangarContents != null && hangarContents.length > 0)
                        {
                            for (obj_id hangarContent : hangarContents) {
                                if (isIdValid(hangarContent) && getGameObjectType(hangarContent) == GOT_data_ship_control_device) {
                                    shipControlDevices[count++] = hangarContent;
                                }
                            }
                        }
                    }
                    return shipControlDevices;
                }
            }
        }
        return null;
    }
    public static obj_id[] findShipControlDevicesInHangarSlot(obj_id player) throws InterruptedException
    {
        obj_id playerHangarSlot = utils.getPlayerHangar(player);
        if (isIdValid(playerHangarSlot))
        {
            obj_id[] hangarContents = getContents(playerHangarSlot);
            if (hangarContents != null && hangarContents.length > 0)
            {
                int count = 0;
                for (obj_id hangarContent1 : hangarContents) {
                    if (isIdValid(hangarContent1) && getGameObjectType(hangarContent1) == GOT_data_ship_control_device) {
                        ++count;
                    }
                }
                if (count > 0)
                {
                    obj_id[] shipHangarSlotControlDevices = new obj_id[count];
                    count = 0;
                    for (obj_id hangarContent : hangarContents) {
                        if (isIdValid(hangarContent) && getGameObjectType(hangarContent) == GOT_data_ship_control_device) {
                            shipHangarSlotControlDevices[count++] = hangarContent;
                        }
                    }
                    return shipHangarSlotControlDevices;
                }
            }
        }
        return null;
    }
    public static obj_id findEmptyShipControlDeviceForShip(obj_id player, obj_id ship) throws InterruptedException
    {
        if (hasObjVar(ship, "shipControlDevice"))
        {
            return getObjIdObjVar(ship, "shipControlDevice");
        }
        else
        {
            return findEmptyShipControlDeviceForPlayer(player);
        }
    }
    public static obj_id findEmptyShipControlDeviceForPlayer(obj_id player) throws InterruptedException
    {
        obj_id[] shipControlDevices = findShipControlDevicesForPlayer(player);
        if (shipControlDevices != null)
        {
            for (obj_id shipControlDevice : shipControlDevices) {
                obj_id[] contents = getContents(shipControlDevice);
                if (contents == null || contents.length == 0) {
                    return shipControlDevice;
                }
            }
        }
        return null;
    }
    public static obj_id getShipFromShipControlDevice(obj_id shipControlDevice) throws InterruptedException
    {
        if (!isIdValid(shipControlDevice))
        {
            return null;
        }
        obj_id[] contents = getContents(shipControlDevice);
        if (contents != null && contents.length > 0)
        {
            return contents[0];
        }
        // Fallback: ship may have been placed/orphaned but SCD still has objvar link
        if (hasObjVar(shipControlDevice, "ship"))
        {
            obj_id linked = getObjIdObjVar(shipControlDevice, "ship");
            if (isIdValid(linked) && exists(linked))
            {
                return linked;
            }
        }
        return null;
    }
    public static int countShipControlDevicesForPlayer(obj_id player) throws InterruptedException
    {
        int count = -1;
        obj_id datapad = utils.getDatapad(player);
        if (isIdValid(datapad))
        {
            obj_id[] datapadContents = getContents(datapad);
            if (datapadContents != null)
            {
                count = 0;
                for (obj_id datapadContent : datapadContents) {
                    if (isIdValid(datapadContent) && getGameObjectType(datapadContent) == GOT_data_ship_control_device) {
                        ++count;
                    }
                }
            }
        }
        return count;
    }
    public static boolean isPlayerBelowShipLimit(obj_id player, obj_id destinationContainer) throws InterruptedException
    {
        if ((getTemplateName(destinationContainer)).equals("object/tangible/datapad/character_hangar_datapad.iff"))
        {
            return true;
        }
        else
        {
            return isPlayerBelowShipLimit(player);
        }
    }
    public static boolean isPlayerBelowShipLimit(obj_id player) throws InterruptedException
    {
        int count = countShipControlDevicesForPlayer(player);
        int limit = SHIP_LIMIT_MAX;
        if (features.hasEpisode3Expansion(player))
        {
            limit = SHIP_LIMIT_EXPANSION;
        }
        if (count < 0)
        {
            return false;
        }
        if (count >= limit)
        {
            return false;
        }
        return true;
    }
    public static obj_id findPilotSlotObjectForShip(obj_id pilot, obj_id ship) throws InterruptedException
    {
        return findPilotSlotObjectDeep(pilot, ship, 0);
    }

    /**
     * Recursive search for ship_pilot / ship_pilot_pob. POB terminals can sit
     * several containment levels under cells; shallow search misses them while
     * the chassis is still nested in the SCD.
     */
    public static obj_id findPilotSlotObjectDeep(obj_id pilot, obj_id node, int depth) throws InterruptedException
    {
        if (!isIdValid(pilot) || !isIdValid(node) || depth > 12)
        {
            return null;
        }
        if (canPutInSlot(pilot, node, SHIP_PILOT_SLOT_NAME) == CEC_SUCCESS)
        {
            return node;
        }
        if (canPutInSlot(pilot, node, POB_SHIP_PILOT_SLOT_NAME) == CEC_SUCCESS)
        {
            return node;
        }
        obj_id[] contents = getContents(node);
        if (contents != null)
        {
            for (obj_id c : contents)
            {
                obj_id found = findPilotSlotObjectDeep(pilot, c, depth + 1);
                if (isIdValid(found))
                {
                    return found;
                }
            }
        }
        String[] cellNames = getCellNames(node);
        if (cellNames != null)
        {
            for (String cellName : cellNames)
            {
                obj_id cell = getCellId(node, cellName);
                if (!isIdValid(cell))
                {
                    continue;
                }
                obj_id found = findPilotSlotObjectDeep(pilot, cell, depth + 1);
                if (isIdValid(found))
                {
                    return found;
                }
            }
        }
        return null;
    }
    public static Vector getContainedPlayers(obj_id obj) throws InterruptedException
    {
        return getContainedPlayers(obj, null);
    }
    public static Vector getContainedPlayers(obj_id obj, Vector players) throws InterruptedException
    {
        if (isPlayer(obj))
        {
            return utils.addElement(players, obj);
        }
        obj_id[] contents = getContents(obj);
        if (contents != null)
        {
            for (obj_id content : contents) {
                players = getContainedPlayers(content, players);
            }
        }
        return players;
    }
    public static void packShip(obj_id ship) throws InterruptedException
    {
        if (getTopMostContainer(ship) != ship)
        {
            return;
        }
        if (space_dungeon.isSpaceDungeon(ship))
        {
            return;
        }
        boolean inSpace = isSpaceScene();
        boolean teleportFixup = hasObjVar(ship, "teleportFixup");
        obj_id pilot = getPilotId(ship);
        obj_id owner = getOwner(ship);
        location shipLoc = getLocation(ship);
        if (debugSpaceTransition)
        {
            LIVE_LOG("TeleportFixup", "packShip, ship=" + ship + ", template=" + getTemplateName(ship) + ", pilot=" + pilot + ", teleportFixup=" + teleportFixup + ", inSpace=" + inSpace + ", owner=" + owner);
        }
        Vector players = getContainedPlayers(ship, null);
        if (players != null)
        {
            for (Object player1 : players) {
                obj_id player = ((obj_id) player1);
                setState(player, STATE_SITTING_ON_CHAIR, false);
                int posture = getPosture(player);
                if (posture == POSTURE_SITTING || posture == POSTURE_PRONE) {
                    setPostureClientImmediate(player, POSTURE_UPRIGHT);
                }
                revokeDroidCommands(player);
                if (debugSpaceTransition) {
                    LOG("space_transition", "packShip dealing with contained player " + player);
                }
                if (player == pilot) {
                    unpilotShip(pilot);
                }
                if (inSpace && player != owner) {
                    teleportPlayerToLaunchLoc(player);
                } else {
                    if (teleportFixup) {
                        copyObjVar(ship, player, "teleportFixup");
                        LIVE_LOG("TeleportFixup", "Copying teleportFixup objVar from " + ship + " to " + player);
                    }
                    setLocation(player, shipLoc);
                }
            }
        }
        if (teleportFixup)
        {
            removeObjVar(ship, "teleportFixup");
            LIVE_LOG("TeleportFixup", "Removing teleportFixup objVar from " + ship);
        }
        if (isIdValid(owner))
        {
            space_combat.clearHyperspace(ship);
            obj_id shipControlDevice = findEmptyShipControlDeviceForShip(owner, ship);
            if (debugSpaceTransition)
            {
                LOG("space_transition", "packShip ship=" + ship + " scd=" + shipControlDevice);
            }
            obj_id droidControlDevice = getDroidControlDeviceForShip(ship);
            LOG("space", "droidControlDevice is " + droidControlDevice);
            if (isIdValid(droidControlDevice))
            {
                obj_id droid = callable.getCDCallable(droidControlDevice);
                LOG("space", "droid is " + droid);
                if (isIdValid(droid))
                {
                    space_combat.removeFlightDroidFromShip(droidControlDevice, droid);
                }
            }
            space_pilot_command.allPurposeShipComponentReset(ship);
            if (isIdValid(shipControlDevice) && putIn(ship, shipControlDevice))
            {
                if (isGameObjectTypeOf(ship, GOT_ship_fighter) && space_utils.isShipWithInterior(ship))
                {
                    obj_id[] shipContents = getAllObjectsInPob(ship);
                    if (shipContents != null && shipContents.length > 0)
                    {
                        for (obj_id shipContent : shipContents) {
                            if (isIdValid(shipContent)) {
                                messageTo(shipContent, "OnPack", null, 1.0f, false);
                            }
                        }
                    }
                }
                return;
            }
        }
        // Do NOT destroy player ships on ground pack failure — that was deleting
        // chassis after a failed atmospheric Store and leaving residual pilot state.
        if (isSpaceScene())
        {
            if (debugSpaceTransition)
            {
                LOG("space_transition", "packShip failed to clean up ship properly, destroying");
            }
            destroyObject(ship);
        }
        else
        {
            LOG("space_transition", "packShip failed on ground — leaving ship intact ship=" + ship);
        }
    }
    public static obj_id[] getAllObjectsInPob(obj_id pob) throws InterruptedException
    {
        obj_id[] cells = getCellIds(pob);
        if (cells == null || cells.length == 0)
        {
            return null;
        }
        Vector objects = new Vector();
        objects.setSize(0);
        for (obj_id cell : cells) {
            obj_id[] contents = getContents(cell);
            if (contents != null && contents.length > 0) {
                for (obj_id content : contents) {
                    utils.addElement(objects, content);
                }
            }
        }
        if (objects == null || objects.size() == 0)
        {
            return null;
        }
        obj_id[] returnList = new obj_id[0];
        if (objects != null)
        {
            returnList = new obj_id[objects.size()];
            objects.toArray(returnList);
        }
        return returnList;
    }
    public static void setShipName(obj_id ship, obj_id player, obj_id shipControlDevice) throws InterruptedException
    {
        String strName = getAssignedName(shipControlDevice);
        LOG("space", "strName is " + strName);
        if (strName == null || strName.length() == 0)
        {
            setName(ship, getName(player));
        }
        else
        {
            setName(ship, getName(player) + " (" + strName + ")");
        }
    }
    // P9 atmospheric flight: snap a ground drop point to terrain height.
    // Player ships often get no server terrain-collision event, so placement
    // at the player's raw Y can bury the chassis. getHeightAtLocation is the
    // script-side fix; setShipLanded still forced after place/unpack.
    // After Call activates the chassis, raise it this many meters above terrain/player.
    // Large POB meshes (Decimator) extend well below the object origin — 20 was still buried.
    public static final float GROUND_SHIP_ABOVE_PLAYER_Y = 10.0f;

    public static location getAtmosphericShipDropLocation(obj_id player) throws InterruptedException
    {
        location loc = getLocation(player);
        if (loc == null)
        {
            return null;
        }
        if (isSpaceScene())
        {
            return loc;
        }
        // Inside a building/cell: keep player cell location
        if (isIdValid(loc.cell))
        {
            return loc;
        }
        // Initial place at player XZ / near feet so pilotShip can extract the chassis.
        // placeShip raises to playerY+20 afterward.
        location drop = new location(loc.x, loc.y, loc.z, loc.area, loc.cell);
        float terrainY = getHeightAtLocation(drop.x, drop.z);
        if (terrainY == terrainY)
        {
            float delta = terrainY - drop.y;
            if (delta < 40.0f && delta > -40.0f)
            {
                drop.y = terrainY + 1.25f;
            }
        }
        LOG("space_transition", "getAtmosphericShipDropLocation: playerY=" + loc.y + " terrainY=" + terrainY + " dropY=" + drop.y);
        return drop;
    }

    public static void snapShipToGroundAndMarkLanded(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || isSpaceScene())
        {
            return;
        }
        location loc = getLocation(ship);
        if (loc == null || isIdValid(loc.cell))
        {
            setShipLanded(ship, true);
            return;
        }
        float terrainY = getHeightAtLocation(loc.x, loc.z);
        if (terrainY == terrainY)
        {
            float delta = terrainY - loc.y;
            if (delta < 40.0f && delta > -40.0f)
            {
                loc.y = terrainY + 1.25f;
                setLocation(ship, loc);
            }
        }
        setShipLanded(ship, true);
    }

    /**
     * Raise ship to world Y = refY + 20m. Prefer Call exterior scriptvars (world
     * coords) — player getLocation while nested in a cell is cell-local and would
     * place the hull wrong / still partially in the ground.
     */
    public static void raiseShipAbovePlayer(obj_id ship, obj_id player) throws InterruptedException
    {
        if (!isIdValid(ship) || isSpaceScene())
        {
            return;
        }
        location shipLoc = getLocation(ship);
        if (shipLoc == null || isIdValid(shipLoc.cell))
        {
            return;
        }
        float refY = shipLoc.y;
        float refX = shipLoc.x;
        float refZ = shipLoc.z;
        String area = shipLoc.area;
        if (isIdValid(player) && utils.hasScriptVar(player, "atmos.exteriorY"))
        {
            refY = utils.getFloatScriptVar(player, "atmos.exteriorY");
            if (utils.hasScriptVar(player, "atmos.exteriorX"))
            {
                refX = utils.getFloatScriptVar(player, "atmos.exteriorX");
            }
            if (utils.hasScriptVar(player, "atmos.exteriorZ"))
            {
                refZ = utils.getFloatScriptVar(player, "atmos.exteriorZ");
            }
            if (utils.hasScriptVar(player, "atmos.exteriorArea"))
            {
                String a = utils.getStringScriptVar(player, "atmos.exteriorArea");
                if (a != null && a.length() > 0)
                {
                    area = a;
                }
            }
        }
        else if (isIdValid(player))
        {
            location playerLoc = getLocation(player);
            if (playerLoc != null && !isIdValid(playerLoc.cell))
            {
                refY = playerLoc.y;
                refX = playerLoc.x;
                refZ = playerLoc.z;
                if (playerLoc.area != null)
                {
                    area = playerLoc.area;
                }
            }
        }
        float terrainY = getHeightAtLocation(refX, refZ);
        float y = refY + GROUND_SHIP_ABOVE_PLAYER_Y;
        if (terrainY == terrainY)
        {
            // Always clear terrain by full clearance (POB mesh hangs below origin).
            float minY = terrainY + GROUND_SHIP_ABOVE_PLAYER_Y;
            if (y < minY)
            {
                y = minY;
            }
        }
        location raised = new location(refX, y, refZ, area, null);
        // Clear landed so engine/client will not clamp Y to terrain under the mesh.
        setShipLanded(ship, false);
        setLocation(ship, raised);
        setLocation(ship, raised);
        location verify = getLocation(ship);
        LOG("space_transition", "raiseShipAbovePlayer: ship=" + ship + " wantY=" + y
            + " gotY=" + (verify != null ? verify.y : -1) + " terrainY=" + terrainY);
    }

    // P9 atmospheric flight: place-ship result codes for player feedback.
    public static final int PLACE_SHIP_OK = 0;
    public static final int PLACE_SHIP_INVALID = 1;
    public static final int PLACE_SHIP_ALREADY_OUT = 2;
    public static final int PLACE_SHIP_BAD_LOCATION = 3;
    public static final int PLACE_SHIP_NOT_IN_WORLD = 4;
    public static final int PLACE_SHIP_RESTORE_FAILED = 5;
    public static final int PLACE_SHIP_POB_ATMOS_UNSUPPORTED = 6;

    // True when the ship is a ground object the player can walk up to —
    // NOT when it is still nested under the player/datapad/SCD.
    // IMPORTANT: isInWorld() is TRUE for objects inside a player's datapad
    // (because the player is in the world). Do not use isInWorld alone.
    public static boolean isShipPlacedInGroundWorld(obj_id ship, obj_id player) throws InterruptedException
    {
        if (!isIdValid(ship) || !exists(ship))
        {
            return false;
        }
        // Packed under player (datapad/SCD/inventory) — NOT placed in the world.
        // isInWorld() is unreliable here because the player is in the world.
        if (utils.isNestedWithin(ship, player))
        {
            return false;
        }
        obj_id parent = getContainedBy(ship);
        if (isIdValid(parent) && hasScript(parent, "space.ship_control_device.ship_control_device"))
        {
            return false;
        }
        if (isIdValid(parent))
        {
            String parentTemplate = getTemplateName(parent);
            if (parentTemplate != null && (parentTemplate.indexOf("ship_control_device") >= 0
                || parentTemplate.indexOf("datapad") >= 0
                || parentTemplate.indexOf("player_inventory") >= 0))
            {
                return false;
            }
        }
        // Must be in a world cell (outdoor world cell counts)
        if (!isInWorldCell(ship))
        {
            return false;
        }
        return true;
    }

    public static boolean restoreShipToControlDevice(obj_id ship, obj_id shipControlDevice) throws InterruptedException
    {
        if (!isIdValid(ship) || !isIdValid(shipControlDevice) || !exists(ship) || !exists(shipControlDevice))
        {
            return false;
        }
        if (getContainedBy(ship) == shipControlDevice)
        {
            setObjVar(shipControlDevice, "ship", ship);
            setObjVar(ship, "shipControlDevice", shipControlDevice);
            return true;
        }

        // Anyone still inside the chassis will block container transfer and leave
        // residual pilot state (pets/combat blocked, stuck after Store).
        Vector players = getContainedPlayers(ship, null);
        if (players != null)
        {
            location shipLoc = getLocation(ship);
            for (Object player1 : players)
            {
                obj_id p = (obj_id) player1;
                if (!isIdValid(p))
                {
                    continue;
                }
                if (getPilotId(ship) == p)
                {
                    unpilotShip(p);
                }
                if (shipLoc != null)
                {
                    location dest = new location(shipLoc.x + 2.0f, shipLoc.y, shipLoc.z + 2.0f, shipLoc.area, shipLoc.cell);
                    if (!isIdValid(shipLoc.cell))
                    {
                        float terrainY = getHeightAtLocation(dest.x, dest.z);
                        if (terrainY == terrainY)
                        {
                            dest.y = terrainY + 0.5f;
                        }
                    }
                    setLocation(p, dest);
                }
            }
        }
        else
        {
            obj_id pilot = getPilotId(ship);
            if (isIdValid(pilot))
            {
                unpilotShip(pilot);
            }
        }

        int can = canPutIn(ship, shipControlDevice);
        LOG("space_transition", "restoreShipToControlDevice: canPutIn=" + can + " ship=" + ship + " scd=" + shipControlDevice
            + " containedBy=" + getContainedBy(ship) + " topMost=" + getTopMostContainer(ship));

        boolean ok = putIn(ship, shipControlDevice);
        if (!ok || getContainedBy(ship) != shipControlDevice)
        {
            // Volume limits on SCD templates can fail normal putIn; overloaded still respects other rules.
            ok = putInOverloaded(ship, shipControlDevice);
        }
        LOG("space_transition", "restoreShipToControlDevice: after putIn ok=" + ok + " containedBy=" + getContainedBy(ship));
        if (getContainedBy(ship) == shipControlDevice)
        {
            setObjVar(shipControlDevice, "ship", ship);
            setObjVar(ship, "shipControlDevice", shipControlDevice);
            return true;
        }
        return false;
    }

    /**
     * Fully eject a player from a ground ship: unpilot + stand beside chassis.
     * Returns true if the player is no longer contained by the ship afterward.
     */
    public static boolean forceEjectPlayerFromShipOnGround(obj_id player, obj_id ship) throws InterruptedException
    {
        // Default: force-load only when truly stuck in a cell (see overload).
        return forceEjectPlayerFromShipOnGround(player, ship, false);
    }

    /**
     * Eject player from a ground ship. forceLoadScreen=true only when the player is
     * stuck inside a cell and needs a hard client refresh. Launch uses false so
     * Call does not flash a load screen when the player was never meant to board.
     */
    public static boolean forceEjectPlayerFromShipOnGround(obj_id player, obj_id ship, boolean forceLoadScreen) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship) || isSpaceScene())
        {
            return !isIdValid(player) || getContainingShip(player) != ship;
        }
        if (getPilotId(ship) == player)
        {
            unpilotShip(player);
        }
        obj_id container = getContainedBy(player);
        if (isIdValid(container))
        {
            if (getObjectInSlot(container, SHIP_PILOT_SLOT_NAME) == player
                || getObjectInSlot(container, POB_SHIP_PILOT_SLOT_NAME) == player)
            {
                unpilotShip(player);
            }
        }
        // Clear pilot states so the client is not stuck "piloting" with no control.
        setState(player, STATE_PILOTING_SHIP, false);
        setState(player, STATE_PILOTING_POB_SHIP, false);
        setState(player, STATE_SHIP_OPERATIONS, false);
        setState(player, STATE_SHIP_GUNNER, false);

        // Do not snap ship to terrain here — that undoes Call raise (+20m).
        // Only mark landed; height is managed by raiseShipAbovePlayer / pilot land.
        if (isIdValid(ship) && exists(ship) && !isSpaceScene())
        {
            setShipLanded(ship, true);
        }

        // Prefer CURRENT ship world position (where the hull is now).
        // Do NOT use atmos.exterior* from Launch — that is the old Call point and
        // teleports the player after flying (looks like ship+player jumped back).
        location dest = null;
        location shipLoc = getLocation(ship);
        if (shipLoc != null && shipLoc.area != null && !isIdValid(shipLoc.cell))
        {
            dest = new location(shipLoc.x + 18.0f, shipLoc.y, shipLoc.z + 18.0f, shipLoc.area, null);
        }
        if (dest == null && space_utils.isShipWithInterior(ship))
        {
            dest = getBuildingEjectLocation(ship);
        }
        if (dest == null && shipLoc != null)
        {
            dest = new location(shipLoc.x + 18.0f, shipLoc.y, shipLoc.z + 18.0f, shipLoc.area, null);
        }
        if (dest == null)
        {
            location pLoc = getLocation(player);
            if (pLoc != null)
            {
                dest = new location(pLoc.x, pLoc.y, pLoc.z, pLoc.area, null);
            }
        }
        // Keep atmos.exterior* — Call raise and interior walk-facing need world refs.
        // Cleared on successful Store, not on every eject.
        if (dest != null)
        {
            dest.cell = null;
            // If dest is still essentially the ship origin, push out.
            location shipLoc2 = getLocation(ship);
            if (shipLoc2 != null)
            {
                float dx = dest.x - shipLoc2.x;
                float dz = dest.z - shipLoc2.z;
                if (dx * dx + dz * dz < 64.0f)
                {
                    dest.x = shipLoc2.x + 18.0f;
                    dest.z = shipLoc2.z + 18.0f;
                }
            }
            float terrainY = getHeightAtLocation(dest.x, dest.z);
            if (terrainY == terrainY)
            {
                dest.y = terrainY + 0.25f;
            }
            setLocation(player, dest);
            // Exterior is world-space; align to hull and clear pilot look-at so
            // cockpit camera does not stick after unpilot.
            float shipYaw = getYaw(ship);
            if (shipYaw == shipYaw)
            {
                setYaw(player, shipYaw);
            }
            setLookAtTarget(player, null);
            // Only hard-warp when requested (stuck-in-cell recovery). Launch stays soft.
            if (forceLoadScreen && dest.area != null)
            {
                warpPlayer(player, dest.area, dest.x, dest.y, dest.z, null, 0.0f, 0.0f, 0.0f, null, true);
            }
            dictionary params = new dictionary();
            params.put("x", dest.x);
            params.put("y", dest.y);
            params.put("z", dest.z);
            params.put("area", dest.area);
            messageTo(player, "handleAtmosExitGroundSnap", params, 0.5f, false);
            messageTo(player, "handleAtmosExitGroundSnap", params, 2.0f, false);
        }

        obj_id still = getContainingShip(player);
        boolean clear = !isIdValid(still) || still != ship;
        // Still nested in ship cells? — hard extract once.
        obj_id top = getTopMostContainer(player);
        if (isIdValid(top) && (top == ship || utils.isNestedWithin(player, ship)))
        {
            clear = false;
            if (dest != null && dest.area != null)
            {
                setLocation(player, dest);
                warpPlayer(player, dest.area, dest.x, dest.y, dest.z, null, 0.0f, 0.0f, 0.0f, null, true);
            }
        }
        LOG("space_transition", "forceEjectPlayerFromShipOnGround: player=" + player + " ship=" + ship
            + " containingShip=" + still + " top=" + top + " clear=" + clear
            + " forceLoad=" + forceLoadScreen);
        return clear;
    }

    /**
     * After leaving the POB pilot seat on the ground: stay INSIDE the ship and
     * walk (same idea as space ops/gunner leaveStation → setLocation in cell).
     * Does not disembark to the planet surface.
     * Call after unpilotShip.
     */
    /**
     * Character (not camera) facing after Enter / Leave Station on ground POB.
     * Pilot seat leaves the body in ship orientation while the chase cam stays
     * world-true — mismatch until full Exit. Restore saved world yaw so body
     * matches world/camera the way Exit already does.
     */
    public static void applyInteriorWalkFacing(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(player) || isSpaceScene())
        {
            return;
        }
        setLookAtTarget(player, null);
        setState(player, STATE_PILOTING_SHIP, false);
        setState(player, STATE_PILOTING_POB_SHIP, false);
        setState(player, STATE_SHIP_OPERATIONS, false);
        setState(player, STATE_SHIP_GUNNER, false);

        float yaw = Float.NaN;
        if (utils.hasScriptVar(player, "atmos.exteriorYaw"))
        {
            yaw = utils.getFloatScriptVar(player, "atmos.exteriorYaw");
        }
        if (yaw != yaw && isIdValid(ship))
        {
            // Fallback: ship world yaw is still world-space (better than seat-local).
            yaw = getYaw(ship);
        }
        if (yaw == yaw)
        {
            setYaw(player, yaw);
        }
    }

    /** Save world-facing yaw for later interior walk realignment. */
    public static void saveExteriorYaw(obj_id player) throws InterruptedException
    {
        if (!isIdValid(player) || isSpaceScene())
        {
            return;
        }
        location loc = getLocation(player);
        if (loc != null && isIdValid(loc.cell))
        {
            return; // already inside something — keep prior exterior yaw if any
        }
        float y = getYaw(player);
        if (y == y)
        {
            utils.setScriptVar(player, "atmos.exteriorYaw", y);
        }
    }

    public static boolean leavePilotSeatIntoShipInterior(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship) || isSpaceScene())
        {
            return false;
        }
        setState(player, STATE_PILOTING_SHIP, false);
        setState(player, STATE_PILOTING_POB_SHIP, false);
        setState(player, STATE_SHIP_OPERATIONS, false);
        setState(player, STATE_SHIP_GUNNER, false);

        // Prefer space ops pattern: step back in local transform of the pilot slot.
        // That keeps position and facing consistent with the cell frame.
        obj_id pilotSlot = findPilotSlotObjectForShip(player, ship);
        if (isIdValid(pilotSlot))
        {
            location slotLoc = getLocation(pilotSlot);
            if (slotLoc != null && isIdValid(slotLoc.cell))
            {
                try
                {
                    vector pos = ((getTransform_o2p(pilotSlot)).move_l(new vector(0.0f, 0.0f, -1.5f))).getPosition_p();
                    location walk = new location(pos.x, pos.y, pos.z, getCurrentSceneName(), slotLoc.cell);
                    setLocation(player, walk);
                    applyInteriorWalkFacing(player, ship);
                    if (utils.isNestedWithin(player, ship) || getTopMostContainer(player) == ship)
                    {
                        LOG("space_transition", "leavePilotSeatIntoShipInterior: OK transform step-back");
                        return true;
                    }
                }
                catch (Exception e)
                {
                    LOG("space_transition", "leavePilotSeatIntoShipInterior: transform step failed: " + e);
                }
                location walk = new location(slotLoc.x, slotLoc.y, slotLoc.z - 1.5f,
                    getCurrentSceneName(), slotLoc.cell);
                setLocation(player, walk);
                applyInteriorWalkFacing(player, ship);
                if (utils.isNestedWithin(player, ship) || getTopMostContainer(player) == ship)
                {
                    LOG("space_transition", "leavePilotSeatIntoShipInterior: OK pilotSlot step-back");
                    return true;
                }
            }
        }

        // Fallback: official start locations (Decimator cockpit walk point).
        Vector starts = getShipStartLocations(ship);
        if (starts != null && starts.size() > 0)
        {
            for (int i = 0; i < starts.size(); i++)
            {
                location sl = (location) starts.get(i);
                if (sl == null || !isIdValid(sl.cell))
                {
                    continue;
                }
                location walk = new location(sl.x + 0.5f, sl.y, sl.z - 1.5f, sl.area, sl.cell);
                setLocation(player, walk);
                applyInteriorWalkFacing(player, ship);
                location after = getLocation(player);
                if (after != null && isIdValid(after.cell)
                    && (after.cell == sl.cell || utils.isNestedWithin(player, ship)
                        || getTopMostContainer(player) == ship))
                {
                    LOG("space_transition", "leavePilotSeatIntoShipInterior: OK startLoc ship=" + ship
                        + " cell=" + sl.cell + " after=" + after);
                    return true;
                }
            }
        }

        // Last: any cell on the ship
        String[] names = getCellNames(ship);
        if (names != null && names.length > 0)
        {
            obj_id cell = getCellId(ship, names[0]);
            if (isIdValid(cell))
            {
                location walk = new location(0.0f, 0.5f, 2.0f, getCurrentSceneName(), cell);
                setLocation(player, walk);
                applyInteriorWalkFacing(player, ship);
                if (utils.isNestedWithin(player, ship) || getTopMostContainer(player) == ship)
                {
                    LOG("space_transition", "leavePilotSeatIntoShipInterior: OK first cell " + names[0]);
                    return true;
                }
            }
        }

        LOG("space_transition", "leavePilotSeatIntoShipInterior: FAIL stay-in-ship ship=" + ship);
        return false;
    }

    /**
     * Safe ground Store: eject occupants, put chassis into the given SCD.
     * Never destroys the ship on failure (unlike packShip).
     */
    public static boolean storeShipInControlDeviceSafe(obj_id ship, obj_id shipControlDevice, obj_id player) throws InterruptedException
    {
        if (!isIdValid(ship) || !isIdValid(shipControlDevice) || !exists(ship) || !exists(shipControlDevice))
        {
            return false;
        }
        if (getContainedBy(ship) == shipControlDevice)
        {
            setObjVar(shipControlDevice, "ship", ship);
            setObjVar(ship, "shipControlDevice", shipControlDevice);
            return true;
        }

        // Eject the acting player first
        if (isIdValid(player))
        {
            forceEjectPlayerFromShipOnGround(player, ship);
        }
        // Eject anyone else still inside
        Vector players = getContainedPlayers(ship, null);
        if (players != null)
        {
            for (Object player1 : players)
            {
                obj_id p = (obj_id) player1;
                if (isIdValid(p))
                {
                    forceEjectPlayerFromShipOnGround(p, ship);
                }
            }
        }
        obj_id pilot = getPilotId(ship);
        if (isIdValid(pilot))
        {
            forceEjectPlayerFromShipOnGround(pilot, ship);
        }

        // If someone is STILL inside, refuse to pack — packing with occupants
        // causes residual pilot state and container-transfer errors.
        players = getContainedPlayers(ship, null);
        if (players != null && players.size() > 0)
        {
            LOG("space_transition", "storeShipInControlDeviceSafe: abort, ship still has occupants");
            return false;
        }

        space_combat.clearHyperspace(ship);
        obj_id droidControlDevice = getDroidControlDeviceForShip(ship);
        if (isIdValid(droidControlDevice))
        {
            obj_id droid = callable.getCDCallable(droidControlDevice);
            if (isIdValid(droid))
            {
                space_combat.removeFlightDroidFromShip(droidControlDevice, droid);
            }
        }
        space_pilot_command.allPurposeShipComponentReset(ship);

        // POB: delay pack so client doors/portals can tear down without crashing
        // in dpvs when the chassis is pulled into the SCD mid-alter.
        if (space_utils.isShipWithInterior(ship) && hasScript(ship, "space.combat.combat_ship"))
        {
            dictionary d = new dictionary();
            d.put("scd", shipControlDevice);
            d.put("player", player);
            messageTo(ship, "handleAtmosDelayedStore", d, 3.5f, false);
            LOG("space_transition", "storeShipInControlDeviceSafe: scheduled delayed POB store ship=" + ship);
            if (isIdValid(player))
            {
                sendSystemMessageTestingOnly(player, "Storing POB ship — please wait a moment...");
            }
            return true;
        }

        boolean ok = restoreShipToControlDevice(ship, shipControlDevice);
        LOG("space_transition", "storeShipInControlDeviceSafe: result=" + ok + " ship=" + ship + " scd=" + shipControlDevice);
        return ok;
    }

    // P9 atmospheric flight: place the ship in the world at the player's
    // location WITHOUT auto-piloting. The player boards later via radial
    // on the ship object (combat_ship). Space launch still uses
    // unpackShipForPlayer which pilots immediately.
    public static boolean placeShipInWorldForPlayer(obj_id player, obj_id ship) throws InterruptedException
    {
        return placeShipInWorldForPlayerWithCode(player, ship) == PLACE_SHIP_OK;
    }

    /**
     * Force the ship to the player's ground drop point and mark it boardable.
     * Always relocates — never leave the chassis at a previous Call location.
     */
    /**
     * Move chassis to the player and put them in the pilot seat.
     * Auto-enter is the only path that reliably syncs the client without a relog.
     */
    public static void relocateShipToPlayerAndPrepareBoard(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship))
        {
            return;
        }
        location dropLoc = getAtmosphericShipDropLocation(player);
        if (dropLoc == null)
        {
            dropLoc = getLocation(player);
        }
        if (dropLoc != null)
        {
            setLocation(ship, dropLoc);
        }
        if (!isSpaceScene())
        {
            float py = getYaw(player);
            if (py == py)
            {
                setYaw(ship, py);
            }
            snapShipToGroundAndMarkLanded(ship);
        }
        setOwner(ship, player);
        if (!hasScript(ship, "space.combat.combat_ship"))
        {
            attachScript(ship, "space.combat.combat_ship");
        }
        // Do not auto-pilot. If we somehow own the seat (prior Call), eject so
        // Launch never leaves the player inside the ship.
        obj_id existingPilot = getPilotId(ship);
        if (existingPilot == player || getContainingShip(player) == ship)
        {
            forceEjectPlayerFromShipOnGround(player, ship);
        }
        if (!isSpaceScene())
        {
            setShipLanded(ship, true);
        }
    }

    /**
     * Board the pilot seat from outside on ground. Clears residual pilot state,
     * forces landed, then pilotShip. Returns true if player is piloting afterward.
     */
    /**
     * Atmospheric Pilot: enter ship on server FIRST, then force client world
     * reload so the client syncs to the piloting state (enter → refresh).
     */
    public static boolean boardShipAsPilotOnGround(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship) || isSpaceScene())
        {
            return false;
        }
        utils.removeScriptVar(player, "atmos.postLaunchEjectPending");

        obj_id currentPilot = getPilotId(ship);
        if (isIdValid(currentPilot) && currentPilot != player)
        {
            LOG("space_transition", "boardShipAsPilotOnGround: other pilot=" + currentPilot);
            return false;
        }
        if (currentPilot == player && getContainingShip(player) == ship)
        {
            setShipLanded(ship, true);
            // Still refresh client so controls sync
            utils.setScriptVar(player, "atmos.boardShipId", ship);
            refreshClientWorldAtPlayer(player, "handleAtmosBoardAfterWorldRefresh");
            return true;
        }

        if (getContainingShip(player) == ship || currentPilot == player)
        {
            forceEjectPlayerFromShipOnGround(player, ship);
        }

        setOwner(ship, player);
        if (!isSpaceScene())
        {
            setShipLanded(ship, true);
        }
        if (!hasScript(ship, "space.combat.combat_ship"))
        {
            attachScript(ship, "space.combat.combat_ship");
        }

        // 1) Enter on server first
        obj_id pilotSlotObject = findPilotSlotObjectForShip(player, ship);
        boolean entered = false;
        if (isIdValid(pilotSlotObject))
        {
            // POB: stand at the pilot terminal cell before pilotShip.
            if (space_utils.isShipWithInterior(ship))
            {
                location slotLoc = getLocation(pilotSlotObject);
                if (slotLoc != null)
                {
                    setLocation(player, slotLoc);
                }
            }
            entered = pilotShip(player, pilotSlotObject) && getPilotId(ship) == player;
            LOG("space_transition", "boardShipAsPilotOnGround: pilotSlot=" + pilotSlotObject
                + " entered=" + entered + " pilotId=" + getPilotId(ship));
        }
        else
        {
            LOG("space_transition", "boardShipAsPilotOnGround: no pilot slot object for ship=" + ship);
        }
        if (!entered)
        {
            obj_id scd = null;
            if (hasObjVar(ship, "shipControlDevice"))
            {
                scd = getObjIdObjVar(ship, "shipControlDevice");
            }
            if (!isIdValid(scd) || !exists(scd))
            {
                obj_id[] scds = findShipControlDevicesForPlayer(player);
                if (scds != null && scds.length > 0)
                {
                    scd = scds[0];
                }
            }
            if (isIdValid(scd) && restoreShipToControlDevice(ship, scd))
            {
                entered = unpackShipForPlayer(player, ship) && getPilotId(ship) == player;
                if (entered)
                {
                    setOwner(ship, player);
                    if (!isSpaceScene())
                    {
                        snapShipToGroundAndMarkLanded(ship);
                        setShipLanded(ship, true);
                    }
                    if (!hasScript(ship, "space.combat.combat_ship"))
                    {
                        attachScript(ship, "space.combat.combat_ship");
                    }
                }
            }
        }

        if (!entered || getPilotId(ship) != player)
        {
            LOG("space_transition", "boardShipAsPilotOnGround: server enter FAIL pilotId=" + getPilotId(ship));
            return false;
        }

        updateShipFaction(ship, player);
        doAIImmunityCheck(ship);
        if (!isSpaceScene())
        {
            setShipLanded(ship, true);
        }

        // Fighters: client world reload helps sync pilot controls.
        // POB: reload tears the player out of the pilot slot / interior — skip it.
        if (space_utils.isShipWithInterior(ship))
        {
            utils.removeScriptVar(player, "atmos.boardShipId");
            LOG("space_transition", "boardShipAsPilotOnGround: POB pilot OK, no client refresh ship=" + ship
                + " pilotId=" + getPilotId(ship));
            return true;
        }

        utils.setScriptVar(player, "atmos.boardShipId", ship);
        LOG("space_transition", "boardShipAsPilotOnGround: server enter OK, refreshing client ship=" + ship);
        refreshClientWorldAtPlayer(player, "handleAtmosBoardAfterWorldRefresh");
        return true;
    }

    /**
     * After enter→refresh warp: re-seat if warp cleared pilot, else leave piloting.
     */
    public static boolean completeBoardShipAfterClientRefresh(obj_id player) throws InterruptedException
    {
        if (!isIdValid(player) || isSpaceScene())
        {
            return false;
        }
        obj_id ship = obj_id.NULL_ID;
        if (utils.hasScriptVar(player, "atmos.boardShipId"))
        {
            ship = utils.getObjIdScriptVar(player, "atmos.boardShipId");
            utils.removeScriptVar(player, "atmos.boardShipId");
        }
        if (!isIdValid(ship) || !exists(ship))
        {
            LOG("space_transition", "completeBoardShipAfterClientRefresh: no ship");
            return false;
        }

        // Already piloting after refresh — done
        if (getPilotId(ship) == player && getContainingShip(player) == ship)
        {
            setShipLanded(ship, true);
            LOG("space_transition", "completeBoardShipAfterClientRefresh: still piloting OK");
            return true;
        }

        setOwner(ship, player);
        setShipLanded(ship, true);
        if (!hasScript(ship, "space.combat.combat_ship"))
        {
            attachScript(ship, "space.combat.combat_ship");
        }

        obj_id pilotSlotObject = findPilotSlotObjectForShip(player, ship);
        if (isIdValid(pilotSlotObject))
        {
            boolean ok = pilotShip(player, pilotSlotObject);
            if (ok && getPilotId(ship) == player)
            {
                updateShipFaction(ship, player);
                doAIImmunityCheck(ship);
                setShipLanded(ship, true);
                LOG("space_transition", "completeBoardShipAfterClientRefresh: re-seat OK");
                return true;
            }
        }

        obj_id scd = null;
        if (hasObjVar(ship, "shipControlDevice"))
        {
            scd = getObjIdObjVar(ship, "shipControlDevice");
        }
        if (!isIdValid(scd) || !exists(scd))
        {
            obj_id[] scds = findShipControlDevicesForPlayer(player);
            if (scds != null && scds.length > 0)
            {
                scd = scds[0];
            }
        }
        if (isIdValid(scd) && restoreShipToControlDevice(ship, scd))
        {
            boolean unpacked = unpackShipForPlayer(player, ship);
            if (unpacked && getPilotId(ship) == player)
            {
                setOwner(ship, player);
                snapShipToGroundAndMarkLanded(ship);
                setShipLanded(ship, true);
                if (!hasScript(ship, "space.combat.combat_ship"))
                {
                    attachScript(ship, "space.combat.combat_ship");
                }
                LOG("space_transition", "completeBoardShipAfterClientRefresh: pack+unpack re-seat OK");
                return true;
            }
        }

        LOG("space_transition", "completeBoardShipAfterClientRefresh: FAIL ship=" + ship);
        return false;
    }

    public static int placeShipInWorldForPlayerWithCode(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || !isIdValid(player))
        {
            LOG("space_transition", "placeShip: invalid ship/player");
            return PLACE_SHIP_INVALID;
        }

        // Clear residual pilot/containment from a previous broken Call/Store so
        // pets, combat, and this Launch are not blocked by a ghost ship state.
        if (!isSpaceScene())
        {
            obj_id existing = getContainingShip(player);
            if (isIdValid(existing))
            {
                forceEjectPlayerFromShipOnGround(player, existing);
            }
            else if (isIdValid(getPilotId(ship)) && getPilotId(ship) == player)
            {
                unpilotShip(player);
            }
        }

        obj_id shipControlDevice = getContainedBy(ship);
        if (!isIdValid(shipControlDevice) && hasObjVar(ship, "shipControlDevice"))
        {
            shipControlDevice = getObjIdObjVar(ship, "shipControlDevice");
        }
        // Ship already out of SCD (previous Call or failed pack): still MOVE it to the player.
        // Old code returned PLACE_SHIP_OK without relocating → "appears at last location".
        if (!isIdValid(shipControlDevice) || getContainedBy(ship) != shipControlDevice)
        {
            if (isShipPlacedInGroundWorld(ship, player) || (!utils.isNestedWithin(ship, player) && !isIdValid(getContainedBy(ship))))
            {
                relocateShipToPlayerAndPrepareBoard(player, ship);
                LOG("space_transition", "placeShip: relocated already-out ship=" + ship + " near player=" + player);
                return PLACE_SHIP_OK;
            }
            if (isIdValid(shipControlDevice) && !restoreShipToControlDevice(ship, shipControlDevice))
            {
                return PLACE_SHIP_RESTORE_FAILED;
            }
            if (!isIdValid(shipControlDevice))
            {
                return PLACE_SHIP_NOT_IN_WORLD;
            }
        }

        location playerLoc = getLocation(player);
        if (playerLoc == null || playerLoc.area == null || playerLoc.area.length() == 0)
        {
            return PLACE_SHIP_BAD_LOCATION;
        }

        // ------------------------------------------------------------------
        // Working activation path: unpackShipForPlayer (setLocation + pilotShip),
        // then unpilot so the player is not left flying. ALWAYS re-snap to the
        // player's current drop point afterward so recall is not stuck at the
        // previous Call coordinates.
        // ------------------------------------------------------------------
        // Remember exterior position BEFORE unpack — for POB, unpack pilots into
        // the interior and we must put the player back outside.
        location exteriorLoc = getLocation(player);
        if (exteriorLoc != null)
        {
            exteriorLoc = new location(exteriorLoc.x, exteriorLoc.y, exteriorLoc.z, exteriorLoc.area, null);
            float ty = getHeightAtLocation(exteriorLoc.x, exteriorLoc.z);
            if (ty == ty)
            {
                exteriorLoc.y = ty + 0.25f;
            }
            utils.setScriptVar(player, "atmos.exteriorX", exteriorLoc.x);
            utils.setScriptVar(player, "atmos.exteriorY", exteriorLoc.y);
            utils.setScriptVar(player, "atmos.exteriorZ", exteriorLoc.z);
            utils.setScriptVar(player, "atmos.exteriorArea", exteriorLoc.area);
            utils.setScriptVar(player, "atmos.exteriorShip", ship);
            saveExteriorYaw(player);
        }

        LOG("space_transition", "placeShip: calling unpackShipForPlayer ship=" + ship + " player=" + player);
        boolean unpacked = unpackShipForPlayer(player, ship);
        LOG("space_transition", "placeShip: unpackShipForPlayer returned " + unpacked
            + " containedBy=" + getContainedBy(ship)
            + " pilotId=" + getPilotId(ship)
            + " nestedInPlayer=" + utils.isNestedWithin(ship, player));

        if (!unpacked)
        {
            restoreShipToControlDevice(ship, shipControlDevice);
            return PLACE_SHIP_NOT_IN_WORLD;
        }

        // Launch: pilotShip activated chassis; unpilot and put player back at Call point,
        // then raise the hull above them so it is visible without burying.
        setOwner(ship, player);
        if (!hasScript(ship, "space.combat.combat_ship"))
        {
            attachScript(ship, "space.combat.combat_ship");
        }
        utils.removeScriptVar(player, "atmos.postLaunchEjectPending");
        if (!isSpaceScene())
        {
            snapShipToGroundAndMarkLanded(ship);
            setShipLanded(ship, true);
            if (getPilotId(ship) == player)
            {
                unpilotShip(player);
            }
            setState(player, STATE_PILOTING_SHIP, false);
            setState(player, STATE_PILOTING_POB_SHIP, false);
            setState(player, STATE_SHIP_OPERATIONS, false);
            setState(player, STATE_SHIP_GUNNER, false);
            if (exteriorLoc != null && exteriorLoc.area != null)
            {
                setLocation(player, exteriorLoc);
            }
            forceEjectPlayerFromShipOnGround(player, ship, false);
            // Lift chassis; leave NOT landed so the client does not snap the hull
            // to terrain (setShipLanded true was undoing the raise visually).
            raiseShipAbovePlayer(ship, player);
            setShipLanded(ship, false);
            utils.setScriptVar(ship, "atmos.callHover", 1);
            dictionary raiseParams = new dictionary();
            raiseParams.put("player", player);
            raiseParams.put("ship", ship);
            messageTo(ship, "handleAtmosRaiseShipAbovePlayer", raiseParams, 0.5f, false);
            messageTo(ship, "handleAtmosRaiseShipAbovePlayer", raiseParams, 2.0f, false);
            messageTo(ship, "handleAtmosRaiseShipAbovePlayer", raiseParams, 5.0f, false);
        }

        boolean placed = isShipPlacedInGroundWorld(ship, player)
            || (getContainedBy(ship) != shipControlDevice && !utils.isNestedWithin(ship, player));

        LOG("space_transition", "placeShip: final placed=" + placed + " ship=" + ship
            + " containedBy=" + getContainedBy(ship) + " pilotId=" + getPilotId(ship)
            + " owner=" + getOwner(ship) + " playerContainingShip=" + getContainingShip(player)
            + " (ejected, no auto-pilot)");

        if (placed)
        {
            return PLACE_SHIP_OK;
        }

        restoreShipToControlDevice(ship, shipControlDevice);
        return PLACE_SHIP_NOT_IN_WORLD;
    }

    /**
     * Force the client to reload the current ground scene around the player
     * (load screen + full object stream). Does not change planet or put the
     * player in a ship. Used after atmospheric Launch so Pilot works without relog.
     */
    public static void refreshClientWorldAtPlayer(obj_id player) throws InterruptedException
    {
        refreshClientWorldAtPlayer(player, null);
    }

    public static void refreshClientWorldAtPlayer(obj_id player, String callback) throws InterruptedException
    {
        if (!isIdValid(player) || !exists(player) || isSpaceScene())
        {
            return;
        }
        location loc = getLocation(player);
        if (loc == null || loc.area == null || loc.area.length() == 0)
        {
            return;
        }
        if (!isIdValid(loc.cell))
        {
            LOG("space_transition", "refreshClientWorldAtPlayer: warp forceLoadScreen area="
                + loc.area + " @ " + loc.x + "," + loc.y + "," + loc.z + " cb=" + callback);
            warpPlayer(player, loc.area, loc.x, loc.y, loc.z, null, 0.0f, 0.0f, 0.0f, callback, true);
            return;
        }
        LOG("space_transition", "refreshClientWorldAtPlayer: warp in-cell forceLoadScreen cell=" + loc.cell + " cb=" + callback);
        warpPlayer(player, loc.area, loc.x, loc.y, loc.z, loc.cell, 0.0f, 0.0f, 0.0f, callback, true);
    }

    public static String getPlaceShipFailureMessage(int code) throws InterruptedException
    {
        switch (code)
        {
            case PLACE_SHIP_OK:
                return "Ship deployed beside you. Target the ship and choose Pilot to board (client will refresh, then enter). Store puts it away.";
            case PLACE_SHIP_INVALID:
                return "Call ship failed: invalid ship or player.";
            case PLACE_SHIP_ALREADY_OUT:
                return "Call ship failed: ship was not packed correctly. Try Call again or relog.";
            case PLACE_SHIP_BAD_LOCATION:
                return "Call ship failed: cannot read your location.";
            case PLACE_SHIP_NOT_IN_WORLD:
                return "Call ship failed: chassis could not leave the control device (no pilot slot found or pilotShip failed). "
                    + "For POB ships, re-grant via Character Builder (PCD must contain a nested ship object). "
                    + "Check server log 'NO piloting' for details.";
            case PLACE_SHIP_RESTORE_FAILED:
                return "Call ship failed: ship left the control device and could not be restored. Relog or re-grant SCD.";
            case PLACE_SHIP_POB_ATMOS_UNSUPPORTED:
                return "Call ship failed for this POB (atmospheric placement). Try again or relog.";
            default:
                return "Call ship failed (error " + code + ").";
        }
    }

    public static boolean unpackShipForPlayer(obj_id player, obj_id ship) throws InterruptedException
    {
        obj_id shipControlDevice = getContainedBy(ship);
        if (debugSpaceTransition)
        {
            LOG("space_transition", "unpackShipForPlayer: scd=" + shipControlDevice + " ship=" + ship + " player=" + player);
        }
        if (isIdValid(shipControlDevice) && isIdValid(ship))
        {
            setShipName(ship, player, shipControlDevice);
            // P9: snap to terrain height — player ships often never get a
            // server terrain-collision callback, so raw player Y can bury the ship.
            location dropLoc = getAtmosphericShipDropLocation(player);
            if (dropLoc == null)
            {
                dropLoc = getLocation(player);
            }
            setLocation(ship, dropLoc);
            if (!isSpaceScene())
            {
                // Face the same way as the caller so interior yaw tracks hull orientation.
                float py = getYaw(player);
                if (py == py)
                {
                    setYaw(ship, py);
                }
                snapShipToGroundAndMarkLanded(ship);
            }
            setObjVar(shipControlDevice, "ship", ship);
            setObjVar(ship, "shipControlDevice", shipControlDevice);
            // Ground Call still needs pilotShip() to extract chassis from SCD into the world;
            // placeShip then unpilots and restores the player at exteriorLoc.

            obj_id pilotSlotObject = findPilotSlotObjectForShip(player, ship);
            if (debugSpaceTransition)
            {
                LOG("space_transition", "unpackShipForPlayer: ship=" + ship + " pilotSlotObject=" + pilotSlotObject + " player=" + player);
            }
            LOG("space", "Trying to pilot the ship for slot " + pilotSlotObject);
            if (isIdValid(pilotSlotObject) && pilotShip(player, pilotSlotObject))
            {
                LOG("space", "I think i piloted");
                updateShipFaction(ship, player);
                doAIImmunityCheck(ship);
                obj_id droidControlDevice = getDroidControlDeviceForShip(ship);
                if (isIdValid(droidControlDevice))
                {
                    obj_id objDroid = callable.getCDCallable(droidControlDevice);
                    if (isIdValid(objDroid))
                    {
                        space_combat.removeFlightDroidFromShip(droidControlDevice, objDroid);
                    }
                }
                if (isIdValid(droidControlDevice))
                {
                    if (debugSpaceTransition)
                    {
                        LOG("space_transition", "unpacking droid from control device " + droidControlDevice + ", ship=" + ship + ", pilotSlotObject=" + pilotSlotObject + ", pilot=" + player);
                    }
                    space_combat.createFlightDroidFromData(droidControlDevice, pilotSlotObject);
                    obj_id objDroid = callable.getCDCallable(droidControlDevice);
                    setAnimationMood(objDroid, "ship");
                    if (!space_utils.isShipWithInterior(ship))
                    {
                        grantDroidCommands(player);
                    }
                    utils.setLocalVar(ship, "droidPcdId", droidControlDevice);
                }
                if (hasScript(ship, "conversation.ship_trainer_01") || hasScript(ship, "conversation.npe_new_jtl_tutorial"))
                {
                    space_utils.openCommChannelAfterLoad(ship, ship);
                }
                if (utils.hasScriptVar(player, "strLaunchPointName"))
                {
                    obj_id objStation = space_combat.getClosestSpaceStation(ship);
                    String strLaunchName = getStringObjVar(objStation, "strName");
                    location locTest = getLocation(objStation);
                    space_transition.updateLaunchWaypoint(player, locTest, strLaunchName);
                    utils.setScriptVar(player, "intNewbieZoneLaunch", 1);
                }
                if (isSpaceBattlefieldZone())
                {
                    if (hasObjVar(ship, "battlefield.locEntryLocation"))
                    {
                        location locEntryLocation = getLocationObjVar(ship, "battlefield.locEntryLocation");
                        warpPlayer(ship, locEntryLocation.area, locEntryLocation.x, locEntryLocation.y, locEntryLocation.z, locEntryLocation.cell, locEntryLocation.x, locEntryLocation.y, locEntryLocation.z);
                    }
                }
                obj_id[] shipContents = trial.getAllObjectsInDungeon(ship);
                if (shipContents != null && shipContents.length > 0)
                {
                    for (obj_id shipContent : shipContents) {
                        if (isIdValid(shipContent)) {
                            messageTo(shipContent, "OnShipUnpack", null, 1.0f, false);
                        }
                    }
                }
                LOG("space", "AOK");
                return true;
            }
            else
            {
                LOG("space", "NO piloting pilotSlot=" + pilotSlotObject
                    + " containedBy=" + getContainedBy(ship) + " scd=" + shipControlDevice);

                // Ground atmospheric: try chassis as pilot slot (some POBs accept it
                // after setLocation), then accept activation if the ship left the SCD
                // even without a successful pilot seat (Call deploys; Pilot boards later).
                if (!isSpaceScene())
                {
                    boolean alt = pilotShip(player, ship) && getPilotId(ship) == player;
                    LOG("space", "ground alt pilotShip(chassis)=" + alt + " pilotId=" + getPilotId(ship));
                    if (alt)
                    {
                        updateShipFaction(ship, player);
                        doAIImmunityCheck(ship);
                        setOwner(ship, player);
                        if (!hasScript(ship, "space.combat.combat_ship"))
                        {
                            attachScript(ship, "space.combat.combat_ship");
                        }
                        return true;
                    }
                    // Ship may already be in the world from setLocation even if pilot failed
                    if (getContainedBy(ship) != shipControlDevice && !utils.isNestedWithin(ship, player))
                    {
                        LOG("space", "ground: ship out of SCD without pilot — treat as deployed");
                        setOwner(ship, player);
                        if (!hasScript(ship, "space.combat.combat_ship"))
                        {
                            attachScript(ship, "space.combat.combat_ship");
                        }
                        snapShipToGroundAndMarkLanded(ship);
                        setShipLanded(ship, true);
                        return true;
                    }
                }
            }
            // Space or total failure: pack back into SCD
            packShip(ship);
        }
        return false;
    }
    public static boolean inNovaOrionBattle(obj_id player) throws InterruptedException
    {
        location here = getLocation(player);
        if (here.area.equals("space_nova_orion"))
        {
            if (hasCompletedCollectionSlot(player, "orion_rank_01_04") || hasCompletedCollectionSlot(player, "nova_rank_01_04"))
            {
                if (!hasCompletedCollectionSlot(player, "orion_rank_01_05") && !hasCompletedCollectionSlot(player, "nova_rank_01_05"))
                {
                    return true;
                }
            }
        }
        return false;
    }
    public static int getPreOverridePlayerSpaceFaction(obj_id player) throws InterruptedException
    {
        obj_id ship = getContainingShip(player);
        if (!isIdValid(ship))
        {
            return 0;
        }
        if (hasObjVar(ship, "spaceFaction.FactionOverride"))
        {
            return getIntObjVar(ship, "spaceFaction.FactionOverride");
        }
        else if (hasSkill(player, "pilot_imperial_navy_novice"))
        {
            return (-615855020);
        }
        else if (hasSkill(player, "pilot_rebel_navy_novice"))
        {
            return (370444368);
        }
        else
        {
            String strFaction = space_flags.getSpaceTrack(player);
            if (strFaction != null)
            {
                if (strFaction.equals(space_flags.PRIVATEER_CORELLIA))
                {
                    return (-1702304293);
                }
                if (strFaction.equals(space_flags.PRIVATEER_TATOOINE))
                {
                    if (space_flags.isInTierTwo(player))
                    {
                        return (1153980303);
                    }
                    else if (space_flags.isInTierThree(player) && (!space_quest.hasReceivedReward(player, "escort", "tatooine_privateer_tier2_4a")))
                    {
                        return (1153980303);
                    }
                    else
                    {
                        return (1808105482);
                    }
                }
                if (strFaction.equals(space_flags.PRIVATEER_NABOO))
                {
                    return (-1360873682);
                }
            }
        }
        return (676821884);
    }
    public static int getPlayerSpaceFaction(obj_id player) throws InterruptedException
    {
        obj_id ship = getContainingShip(player);
        if (!isIdValid(ship))
        {
            return 0;
        }
        if (hasObjVar(player, township.OBJVAR_NOVA_ORION_FACTION))
        {
            if (inNovaOrionBattle(player))
            {
                String novaOrionFaction = getStringObjVar(player, township.OBJVAR_NOVA_ORION_FACTION);
                if (novaOrionFaction != null)
                {
                    if (novaOrionFaction.equals("nova") || novaOrionFaction.equals("orion"))
                    {
                        int[] alliedFactions = new int[3];
                        if (novaOrionFaction.equals("nova"))
                        {
                            alliedFactions[0] = (1089617796);
                            alliedFactions[1] = (-160237431);
                            alliedFactions[2] = getPreOverridePlayerSpaceFaction(player);
                            shipSetSpaceFactionAllies(ship, alliedFactions);
                            return (1089617796);
                        }
                        else
                        {
                            alliedFactions[0] = (2043986206);
                            alliedFactions[1] = (-160237431);
                            alliedFactions[2] = getPreOverridePlayerSpaceFaction(player);
                            shipSetSpaceFactionAllies(ship, alliedFactions);
                            return (2043986206);
                        }
                    }
                }
            }
        }
        if (hasObjVar(ship, "spaceFaction.FactionOverride"))
        {
            int[] alliedFactions = new int[1];
            alliedFactions[0] = (-160237431);
            shipSetSpaceFactionAllies(ship, alliedFactions);
            return getIntObjVar(ship, "spaceFaction.FactionOverride");
        }
        else if (hasSkill(player, "pilot_imperial_navy_novice"))
        {
            int[] alliedFactions = new int[2];
            alliedFactions[0] = (-1360873682);
            alliedFactions[1] = (-160237431);
            shipSetSpaceFactionAllies(ship, alliedFactions);
            return (-615855020);
        }
        else if (hasSkill(player, "pilot_rebel_navy_novice"))
        {
            int[] alliedFactions = new int[2];
            alliedFactions[0] = (-1153515706);
            alliedFactions[1] = (-160237431);
            shipSetSpaceFactionAllies(ship, alliedFactions);
            return (370444368);
        }
        else
        {
            String strFaction = space_flags.getSpaceTrack(player);
            if (strFaction != null)
            {
                if (strFaction.equals(space_flags.PRIVATEER_CORELLIA))
                {
                    int[] alliedFactions = new int[1];
                    alliedFactions[0] = (-160237431);
                    shipSetSpaceFactionAllies(ship, alliedFactions);
                    return (-1702304293);
                }
                if (strFaction.equals(space_flags.PRIVATEER_TATOOINE))
                {
                    int[] alliedFactions = new int[1];
                    alliedFactions[0] = (-160237431);
                    shipSetSpaceFactionAllies(ship, alliedFactions);
                    if (space_flags.isInTierTwo(player))
                    {
                        return (1153980303);
                    }
                    else if (space_flags.isInTierThree(player) && (!space_quest.hasReceivedReward(player, "escort", "tatooine_privateer_tier2_4a")))
                    {
                        return (1153980303);
                    }
                    else
                    {
                        return (1808105482);
                    }
                }
                if (strFaction.equals(space_flags.PRIVATEER_NABOO))
                {
                    int[] alliedFactions = new int[2];
                    alliedFactions[0] = (-615855020);
                    alliedFactions[1] = (-160237431);
                    shipSetSpaceFactionAllies(ship, alliedFactions);
                    return (-1360873682);
                }
            }
        }
        int[] alliedFactions = new int[1];
        alliedFactions[0] = (-160237431);
        shipSetSpaceFactionAllies(ship, alliedFactions);
        return (676821884);
    }
    public static void grantDroidCommands(obj_id objPlayer) throws InterruptedException
    {
        String[] strDroidCommands = space_combat.getProgrammedDroidCommands(objPlayer);
        if (strDroidCommands != null && strDroidCommands.length > 0)
        {
            for (String strDroidCommand : strDroidCommands) {
                grantCommand(objPlayer, "droid+" + strDroidCommand);
            }
        }
    }
    public static void revokeDroidCommands(obj_id objPlayer) throws InterruptedException
    {
        String[] strDroidCommands = space_combat.getProgrammedDroidCommands(objPlayer);
        if (strDroidCommands != null && strDroidCommands.length > 0)
        {
            for (String strDroidCommand : strDroidCommands) {
                revokeCommand(objPlayer, "droid+" + strDroidCommand);
            }
        }
    }
    public static void teleportPlayerToLaunchLoc(obj_id player) throws InterruptedException
    {
        teleportPlayerToLaunchLoc(player, false);
    }
    public static void teleportPlayerToLaunchLoc(obj_id player, boolean hyperspace) throws InterruptedException
    {
        location worldLaunchLoc = getLocationObjVar(player, "space.launch.worldLoc");
        if (hasObjVar(player, "npe.phase_number"))
        {
            if (npe.teleportPlayerToLaunchLoc(player, hyperspace))
            {
                return;
            }
        }
        if (worldLaunchLoc == null)
        {
            sendSystemMessageTestingOnly(player, "You do not have a launch location. This usually means that you didn't use the launch terminal. Please do so.");
            worldLaunchLoc = new location(5.0f, 195.0f, 5.0f, "tatooine");
        }
        float theta = rand() * (2.0f * (float)Math.PI);
        float radius = 2.0f + rand() * 3.0f;
        worldLaunchLoc.x += radius * StrictMath.cos(theta);
        worldLaunchLoc.z += radius * StrictMath.sin(theta);
        if (hyperspace)
        {
            hyperspacePlayerToLocation(player, worldLaunchLoc.area, worldLaunchLoc.x, worldLaunchLoc.y, worldLaunchLoc.z, null, worldLaunchLoc.x, worldLaunchLoc.y, worldLaunchLoc.z, null, false);
        }
        else
        {
            warpPlayer(player, worldLaunchLoc.area, worldLaunchLoc.x, worldLaunchLoc.y, worldLaunchLoc.z, null, worldLaunchLoc.x, worldLaunchLoc.y, worldLaunchLoc.z, null, false);
        }
    }
    public static location getShipBoardingDestination(obj_id objShip) throws InterruptedException
    {
        transform[] trEntrances = utils.getTransformArrayScriptVar(objShip, "locEntrance");
        if (trEntrances == null)
        {
            obj_id[] objCells = getContents(objShip);
            if (objCells == null || objCells.length == 0)
            {
                return null;
            }
            obj_id objCell = objCells[0];
            location locTest = new location();
            locTest.cell = objCell;
            LOG("space", "HORRIBLY BAD SHIP OF OBJECT ID " + objShip);
            return locTest;
        }
        obj_id[] objCells = utils.getObjIdArrayScriptVar(objShip, "locEntranceCells");
        int intTest = rand(0, trEntrances.length - 1);
        transform tr = trEntrances[intTest];
        location locDestination = space_utils.getLocationFromTransform(tr);
        locDestination.cell = objCells[intTest];
        return locDestination;
    }
    public static boolean isShipBoardable(obj_id objPlayer, obj_id objShip) throws InterruptedException
    {
        obj_id objPlayerShip = getPilotedShip(objPlayer);
        if (!isIdValid(objPlayerShip))
        {
            string_id strSpam = new string_id("space/space_advanced", "no_board_without_ship");
            sendSystemMessage(objPlayer, strSpam);
            return false;
        }
        if (!isIdValid(objShip))
        {
            string_id strSpam = new string_id("space/space_advanced", "no_ship_targeted");
            sendSystemMessage(objPlayer, strSpam);
            return false;
        }
        obj_id[] objCells = getContents(objShip);
        if (objCells == null || objCells.length == 0)
        {
            string_id strSpam = new string_id("space/space_advanced", "not_boardable");
            sendSystemMessage(objPlayer, strSpam);
            return false;
        }
        return true;
    }
    public static Vector getShipStartLocations(obj_id ship) throws InterruptedException
    {
        Vector startLocations = null;
        String shipTemplateName = getTemplateName(ship);
        String[] templateNames = dataTableGetStringColumn(DATATABLE_SHIP_START_LOCATIONS, COLUMN_TEMPLATE);
        if (templateNames != null)
        {
            int rowIndex = 0;
            for (; rowIndex < templateNames.length; ++rowIndex)
            {
                if (templateNames[rowIndex].equals(shipTemplateName))
                {
                    break;
                }
            }
            if (rowIndex < templateNames.length)
            {
                do
                {
                    String slotName = dataTableGetString(DATATABLE_SHIP_START_LOCATIONS, rowIndex, COLUMN_SLOT);
                    if (slotName != null && slotName.length() > 0)
                    {
                        int slot = utils.stringToInt(slotName.substring(11));
                        if (slot > 0 && isShipSlotInstalled(ship, slot + ship_chassis_slot_type.SCST_weapon_0))
                        {
                            startLocations = utils.addElement(startLocations, new location(0.0f, 0.0f, 0.0f, slotName, null));
                        }
                        else
                        {
                            LOG("space", "Launching into space.  Turret not installed.  slotName: " + slotName + " slot: " + slot);
                        }
                    }
                    else
                    {
                        String cellName = dataTableGetString(DATATABLE_SHIP_START_LOCATIONS, rowIndex, COLUMN_CELL);
                        obj_id cell = getCellId(ship, cellName);
                        if (isIdValid(cell))
                        {
                            float x = dataTableGetFloat(DATATABLE_SHIP_START_LOCATIONS, rowIndex, COLUMN_X);
                            float y = dataTableGetFloat(DATATABLE_SHIP_START_LOCATIONS, rowIndex, COLUMN_Y);
                            float z = dataTableGetFloat(DATATABLE_SHIP_START_LOCATIONS, rowIndex, COLUMN_Z);
                            startLocations = utils.addElement(startLocations, new location(x, y, z, getCurrentSceneName(), cell));
                        }
                    }
                    ++rowIndex;
                } while (rowIndex < templateNames.length && templateNames[rowIndex].length() == 0);
            }
        }
        return startLocations;
    }
    public static void updateLaunchWaypoint(obj_id objPlayer, location locTest, String strName) throws InterruptedException
    {
        obj_id objLaunchWaypoint = null;
        if (!hasObjVar(objPlayer, "space.objLaunchWaypoint"))
        {
            LOG("space", "HASOBJVAR");
            objLaunchWaypoint = createWaypointInDatapad(objPlayer, locTest);
        }
        else
        {
            objLaunchWaypoint = getObjIdObjVar(objPlayer, "space.objLaunchWaypoint");
            LOG("space", "objLaunchWaypoint of " + objLaunchWaypoint + " does not exist");
            if (!space_utils.hasWaypointInDatapad(objPlayer, objLaunchWaypoint))
            {
                objLaunchWaypoint = createWaypointInDatapad(objPlayer, locTest);
            }
            else
            {
                setWaypointLocation(objLaunchWaypoint, locTest);
            }
        }
        if (isIdValid(objLaunchWaypoint))
        {
            setObjVar(objPlayer, "space.objLaunchWaypoint", objLaunchWaypoint);
            setWaypointActive(objLaunchWaypoint, true);
            string_id strSpam = new string_id("launch_names", strName);
            setWaypointName(objLaunchWaypoint, utils.packStringId(strSpam));
        }
    }
    public static void updateShipFaction(obj_id ship, obj_id player) throws InterruptedException
    {
        int spaceFaction = 0;
        if (isSpaceBattlefieldZone() && hasObjVar(ship, "intBattlefieldTeam"))
        {
            spaceFaction = getIntObjVar(ship, "intBattlefieldTeam");
            shipSetSpaceFaction(ship, spaceFaction);
        }
        else
        {
            spaceFaction = getPlayerSpaceFaction(player);
            shipSetSpaceFaction(ship, spaceFaction);
            updatePVPStatus(ship);
        }
    }
    public static void setPlayerOvert(long player) throws InterruptedException
    {
        final obj_id playerObjId = obj_id.getObjId(player);
        if (isIdValid(playerObjId) && exists(playerObjId) && playerObjId.isAuthoritative())
        {
            setPlayerOvert(playerObjId);
        }
    }
    public static void setPlayerOvert(obj_id player) throws InterruptedException
    {
        obj_id objShip = getContainingShip(player);
        if (isIdValid(objShip))
        {
            setObjVar(objShip, "spaceFaction.overt", 1);
            messageTo(objShip, "checkSpacePVPStatus", null, 30, false);
        }
        return;
    }
    public static void setPlayerOvert(obj_id player, int overrideFaction) throws InterruptedException
    {
        obj_id ship = getContainingShip(player);
        setObjVar(ship, "spaceFaction.FactionOverride", overrideFaction);
        shipSetSpaceFaction(ship, overrideFaction);
        setPlayerOvert(player);
    }
    public static void clearOvertStatus(long objShip) throws InterruptedException
    {
        final obj_id objShipObjId = obj_id.getObjId(objShip);
        if (isIdValid(objShipObjId) && exists(objShipObjId) && objShipObjId.isAuthoritative())
        {
            clearOvertStatus(objShipObjId);
        }
    }
    public static void clearOvertStatus(obj_id objShip) throws InterruptedException
    {
        if (isIdValid(objShip))
        {
            if (hasObjVar(objShip, "intBattlefieldTeam"))
            {
                removeObjVar(objShip, "intBattlefieldTeam");
            }
            removeObjVar(objShip, "spaceFaction.FactionOverride");
            removeObjVar(objShip, "spaceFaction.overt");
            space_utils.notifyObject(objShip, "checkSpacePVPStatus", null);
        }
    }
    public static void updatePVPStatus(obj_id objShip) throws InterruptedException
    {
        if (hasObjVar(objShip, "spaceFaction.FactionOverride"))
        {
            shipSetSpaceFaction(objShip, getIntObjVar(objShip, "spaceFaction.FactionOverride"));
        }
        if (hasObjVar(objShip, "spaceFaction.overt"))
        {
            obj_id objPlayer = getPilotId(objShip);
            int intType = pvpGetType(objShip);
            if (intType != PVPTYPE_DECLARED)
            {
                if (isIdValid(objPlayer))
                {
                    space_utils.sendSystemMessage(objPlayer, SID_PVP_NOW_OVERT);
                }
            }
            pvpMakeDeclared(objShip);
        }
        else
        {
            int intType = pvpGetType(objShip);
            if (intType == PVPTYPE_DECLARED)
            {
                obj_id objPlayer = getPilotId(objShip);
                if (isIdValid(objPlayer))
                {
                    space_utils.sendSystemMessage(objPlayer, SID_PVP_NOW_NEUTRAL);
                }
            }
            pvpSetAlignedFaction(objShip, 0);
            pvpMakeNeutral(objShip);
        }
    }
    public static void doAIImmunityCheck(obj_id objShip) throws InterruptedException
    {
        String strChassisType = getShipChassisType(objShip);
        ship_ai.unitRemoveFromAllAttackTargetLists(objShip);
        if (strChassisType.equals("player_sorosuub_space_yacht"))
        {
            LOG("space", "Setting sorosuub to be immune");
            ship_ai.unitSetAutoAggroImmune(objShip, true);
        }
        else
        {
            ship_ai.unitSetAutoAggroImmuneTime(objShip, 60);
        }
/*        if (hasObjVar(getOwner(objShip), "gm"))
        {
            ship_ai.unitRemoveFromAllAttackTargetLists(objShip);
            ship_ai.unitSetAutoAggroImmune(objShip, true);
            ship_ai.unitSetDamageAggroImmune(objShip, true);
            sendSystemMessageTestingOnly(getOwner(objShip), "aiIgnore[ON]: AI will no longer aggro you or fight back.");
        }*/
    }
    public static void adjustShipTeleportFixupInSpaceScene(obj_id ship) throws InterruptedException
    {
        if (hasObjVar(ship, "teleportFixup"))
        {
            String fixupCallback = getStringObjVar(ship, "teleportFixup.callback");
            LIVE_LOG("TeleportFixup", "Removing teleport fixup from " + ship);
            removeObjVar(ship, "teleportFixup");
            if (fixupCallback != null && fixupCallback.length() > 0)
            {
                setObjVar(ship, "teleportFixup.callback", fixupCallback);
            }
        }
    }
    public static int getNextStartIndex(Vector shipStartLocations, int lastStartIndex) throws InterruptedException
    {
        int startIndex = lastStartIndex + 1;
        if (startIndex > shipStartLocations.size())
        {
            for (startIndex = 1; startIndex <= shipStartLocations.size(); ++startIndex)
            {
                if (((location)shipStartLocations.get(startIndex - 1)).cell != null)
                {
                    break;
                }
            }
        }
        return startIndex;
    }
    public static void launch(obj_id player, obj_id ship, obj_id[] passengers, location warpLocation, location groundLoc) throws InterruptedException
    {
        Vector passengersToWarp = utils.addElement(null, player);
        Vector passengerStartIndexes = utils.addElement(null, 0);
        Vector shipStartLocations = space_transition.getShipStartLocations(ship);
        if (null != passengers)
        {
            if (shipStartLocations != null && shipStartLocations.size() > 0)
            {
                int startIndex = 0;
                location playerLoc = getLocation(player);
                for (obj_id passenger : passengers) {
                    if (passenger != player) {
                        if (features.isSpaceEdition(passenger)) {
                            startIndex = space_transition.getNextStartIndex(shipStartLocations, startIndex);
                            if (startIndex <= shipStartLocations.size()) {
                                passengersToWarp = utils.addElement(passengersToWarp, passenger);
                                passengerStartIndexes = utils.addElement(passengerStartIndexes, startIndex);
                            }
                        } else {
                            string_id strSpam = new string_id("space/space_interaction", "no_space_expansion");
                            sendSystemMessage(passenger, strSpam);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < passengersToWarp.size(); ++i)
        {
            obj_id passenger = ((obj_id)passengersToWarp.get(i));
            int passengerStartIndex = (Integer) passengerStartIndexes.get(i);
            space_transition.setLaunchInfo(passenger, ship, passengerStartIndex, groundLoc);
            warpPlayer(passenger, warpLocation.area, warpLocation.x, warpLocation.y, warpLocation.z, null, warpLocation.x, warpLocation.y, warpLocation.z);
        }
    }
    public static void exitCapitalShip(obj_id objPlayer, transform trTest) throws InterruptedException
    {
        obj_id objContainingShip = space_transition.getContainingShip(objPlayer);
        location locTest = getLocationObjVar(objContainingShip, "locLaunchLocation");
        obj_id[] objControlDevices = space_transition.findShipControlDevicesForPlayer(objPlayer);
        LOG("space", "control device is " + objControlDevices[0]);
        obj_id ship = space_transition.getShipFromShipControlDevice(objControlDevices[0]);
        setObjVar(objPlayer, "space.launch.worldLoc", locTest);
        setObjVar(objPlayer, "space.launch.ship", ship);
        setObjVar(objPlayer, "space.launch.startIndex", 0);
        setLocation(ship, locTest);
        LOG("space", "Putting ship at " + locTest);
        dictionary dctParams = new dictionary();
        dctParams.put("objShip", ship);
        dctParams.put("locTest", locTest);
        dctParams.put("trTest", trTest);
        messageTo(objPlayer, "doDelayedPilot", dctParams, 1.0f, false);
    }
    public static void enterCapitalShip(obj_id objPlayer, transform trTest, obj_id objCell) throws InterruptedException
    {
        space_transition.packShip(space_transition.getContainingShip(objPlayer));
        location locTest = new location();
        locTest.cell = objCell;
        setLocation(objPlayer, locTest);
        LOG("space", "sending player to " + locTest);
        setTransform_o2p(objPlayer, trTest);
    }
}
