package script.space.terminal;

import script.*;
import script.library.*;

/**
 * Temporary starship terminal, spawned beside a player by Ship Travel (ship radial) when no real
 * starship terminal is nearby. The client's starship terminal window needs a GOT_terminal_space
 * object within 16 m, so this stand-in lets the normal (free, own-ship) travel window open anywhere.
 * Only the owner can use it, only for starport-to-starport travel, and it removes itself.
 */
public class terminal_space_temp extends script.space.terminal.terminal_space
{
    public terminal_space_temp()
    {
    }
    public static final String VAR_OWNER = "atmosTempTerminal.owner";
    public static final String VAR_EXPIRE = "atmosTempTerminal.expire";
    public static final int LIFETIME_SECONDS = 180;
    public static final String VAR_SHIP = "atmosTempTerminal.ship";
    public static final String MSG_EXPIRE = "msgAtmosTempTerminalExpire";
    public static final String MSG_LAUNCH = "msgAtmosTempTerminalLaunch";

    private void prepare(obj_id self) throws InterruptedException
    {
        setName(self, "Ship Travel Terminal");
        // terminal_space.OnAboutToLaunchIntoSpace silently does nothing unless this is set; it is only used for space launches.
        utils.setScriptVar(self, "space.loc.space", new location(0.0f, 0.0f, 0.0f, "space_tatooine"));
    }
    public int OnAttach(obj_id self) throws InterruptedException
    {
        prepare(self);
        setObjVar(self, VAR_EXPIRE, getGameTime() + LIFETIME_SECONDS);
        messageTo(self, MSG_EXPIRE, null, (float)LIFETIME_SECONDS, false);
        return SCRIPT_CONTINUE;
    }
    public int OnInitialize(obj_id self) throws InterruptedException
    {
        // Also runs after a server restart: drop expired terminals, re-arm the rest.
        int expire = hasObjVar(self, VAR_EXPIRE) ? getIntObjVar(self, VAR_EXPIRE) : 0;
        int remaining = expire - getGameTime();
        if (remaining <= 0)
        {
            destroyObject(self);
            return SCRIPT_CONTINUE;
        }
        prepare(self);
        messageTo(self, MSG_EXPIRE, null, (float)remaining, false);
        return SCRIPT_CONTINUE;
    }
    public int OnPreloadComplete(obj_id self) throws InterruptedException
    {
        return SCRIPT_CONTINUE;
    }
    public int msgAtmosTempTerminalExpire(obj_id self, dictionary params) throws InterruptedException
    {
        destroyObject(self);
        return SCRIPT_CONTINUE;
    }
    public int msgAtmosTempTerminalLaunch(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null)
        {
            return SCRIPT_CONTINUE;
        }
        obj_id player = params.getObjId("player");
        obj_id scd = params.getObjId("scd");
        obj_id ship = params.getObjId("ship");
        if (!isIdValid(player) || !exists(player))
        {
            return SCRIPT_CONTINUE;
        }
        boolean stored = isIdValid(ship) && isIdValid(scd) && exists(ship) && getContainedBy(ship) == scd;
        if (!stored)
        {
            int tries = params.getInt("tries");
            if (tries >= 15)
            {
                sendSystemMessageTestingOnly(player, "Ship Travel failed: your ship could not be stored. Try again outside the ship.");
                return SCRIPT_CONTINUE;
            }
            params.put("tries", tries + 1);
            messageTo(self, MSG_LAUNCH, params, 1.0f, false);
            return SCRIPT_CONTINUE;
        }
        OnAboutToLaunchIntoSpace(self, player, scd, params.getObjIdArray("members"), params.getString("planet"), params.getString("point"));
        return SCRIPT_CONTINUE;
    }
    public int OnAboutToLaunchIntoSpace(obj_id self, obj_id player, obj_id shipControlDevice, obj_id[] membersApprovedByShipOwner, String destinationGroundPlanet, String destinationGroundTravelPoint) throws InterruptedException
    {
        if (!hasObjVar(self, VAR_OWNER) || getObjIdObjVar(self, VAR_OWNER) != player)
        {
            sendSystemMessageTestingOnly(player, "This terminal belongs to another pilot.");
            return SCRIPT_CONTINUE;
        }
        if (destinationGroundPlanet == null || destinationGroundPlanet.equals(""))
        {
            sendSystemMessageTestingOnly(player, "This terminal only supports starport travel. Use a real starship terminal to launch into space.");
            return SCRIPT_CONTINUE;
        }
        // The ship must be stored before the player leaves: store it now if it is still out.
        obj_id ship = hasObjVar(self, VAR_SHIP) ? getObjIdObjVar(self, VAR_SHIP) : obj_id.NULL_ID;
        if (isIdValid(ship) && exists(ship) && getContainedBy(ship) != shipControlDevice)
        {
            boolean started = space_transition.storeShipInControlDeviceSafe(ship, shipControlDevice, player);
            if (!started)
            {
                sendSystemMessageTestingOnly(player, "Ship Travel failed: your ship could not be stored. Move clear of it and try again.");
                return SCRIPT_CONTINUE;
            }
            if (getContainedBy(ship) != shipControlDevice)
            {
                // POB ships pack after a delay: finish the trip once the ship is really inside its device.
                dictionary d = new dictionary();
                d.put("player", player);
                d.put("scd", shipControlDevice);
                d.put("ship", ship);
                d.put("members", membersApprovedByShipOwner);
                d.put("planet", destinationGroundPlanet);
                d.put("point", destinationGroundTravelPoint);
                d.put("tries", 0);
                sendSystemMessageTestingOnly(player, "Storing your ship before travel...");
                messageTo(self, MSG_LAUNCH, d, 1.0f, false);
                return SCRIPT_CONTINUE;
            }
        }
        int result = super.OnAboutToLaunchIntoSpace(self, player, shipControlDevice, membersApprovedByShipOwner, destinationGroundPlanet, destinationGroundTravelPoint);
        messageTo(self, MSG_EXPIRE, null, 10.0f, false);
        return result;
    }
}
