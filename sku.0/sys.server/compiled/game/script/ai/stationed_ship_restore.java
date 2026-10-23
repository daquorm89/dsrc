package script.ai;

import script.*;
import script.library.*;

/**
 * Attached to a ship (POB hull) that holds stowed loose droids. See script.library.stationed_droid
 * ("ships" and "restore" sections) for the design.
 *
 * When a ship with loose droids aboard is packed, each droid is stowed and a record is written on the
 * ship. When the ship is called out again, space_transition schedules handleRestoreLooseDroids here;
 * the droids are then recreated one at a time at their recorded posts and set loose again.
 *
 * Name: not "stationed_droid" because that already exists in this package (the droid's own script).
 */
public class stationed_ship_restore extends script.base_script
{
    public stationed_ship_restore()
    {
    }

    /**
     * A ship that is out in the world and still holds records (server restart in the middle of a
     * restore, or an owner who was offline at the last unpack) gets another pass.
     */
    public int OnInitialize(obj_id self) throws InterruptedException
    {
        if (hasObjVar(self, script.library.stationed_droid.OBJVAR_SHIP_RECORDS) && !isIdValid(getContainedBy(self)) && !isSpaceScene())
        {
            scheduleRestorePass(self, 0, 15.0f);
        }
        return SCRIPT_CONTINUE;
    }

    public int handleRestoreLooseDroids(obj_id self, dictionary params) throws InterruptedException
    {
        int attempt = params == null ? 0 : params.getInt("attempt");
        if (script.library.stationed_droid.restoreDroidsAboard(self, attempt) && attempt + 1 < script.library.stationed_droid.RESTORE_MAX_TRIES)
        {
            scheduleRestorePass(self, attempt + 1, script.library.stationed_droid.RESTORE_RETRY_SEC);
        }
        return SCRIPT_CONTINUE;
    }

    /** Keeps a droid that is being restored still and on its post until it is set loose. */
    public int handleHoldRestoringDroid(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null)
        {
            return SCRIPT_CONTINUE;
        }
        int n = params.getInt("n");
        obj_id droid = params.getObjId("droid");
        if (n < script.library.stationed_droid.RESTORE_HOLD_MAX && script.library.stationed_droid.holdRestoringDroid(self, droid, params.getString("record")))
        {
            dictionary again = new dictionary();
            again.put("droid", droid);
            again.put("record", params.getString("record"));
            again.put("n", n + 1);
            messageTo(self, "handleHoldRestoringDroid", again, script.library.stationed_droid.RESTORE_HOLD_SEC, false);
        }
        return SCRIPT_CONTINUE;
    }

    public int handleFinishLooseDroid(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null)
        {
            return SCRIPT_CONTINUE;
        }
        int tries = params.getInt("tries");
        obj_id droid = params.getObjId("droid");
        obj_id owner = params.getObjId("owner");
        String record = params.getString("record");
        if (script.library.stationed_droid.finishAboardRestore(self, droid, owner, record, tries))
        {
            dictionary again = new dictionary();
            again.put("droid", droid);
            again.put("owner", owner);
            again.put("record", record);
            again.put("tries", tries + 1);
            messageTo(self, "handleFinishLooseDroid", again, script.library.stationed_droid.RESTORE_FINISH_DELAY_SEC, false);
            return SCRIPT_CONTINUE;
        }
        // This droid is done (restored or given up): continue with the next stowed droid, if any.
        if (script.library.stationed_droid.getShipRecords(self).length > 0)
        {
            scheduleRestorePass(self, 0, 0.5f);
        }
        return SCRIPT_CONTINUE;
    }

    private static void scheduleRestorePass(obj_id ship, int attempt, float delay) throws InterruptedException
    {
        dictionary d = new dictionary();
        d.put("attempt", attempt);
        messageTo(ship, "handleRestoreLooseDroids", d, delay, false);
    }
}
