package script.ai;

import script.*;
import script.library.*;

/**
 * Attached to a POB ship that holds animals stowed from its pasture (see script.library.pasture_lib,
 * "ships"). When the ship is called out again, space_transition schedules handleRestorePasture here and
 * the animals are recreated in their rooms and put out to pasture again.
 */
public class pasture_ship_restore extends script.base_script
{
    public pasture_ship_restore()
    {
    }

    /** A ship that is out in the world and still holds records (restart, owner was offline) gets another pass. */
    public int OnInitialize(obj_id self) throws InterruptedException
    {
        if (pasture_lib.needsRestorePass(self) && !isIdValid(getContainedBy(self)) && !isSpaceScene())
        {
            schedule(self, 0, 15.0f);
        }
        return SCRIPT_CONTINUE;
    }

    public int handleRestorePasture(obj_id self, dictionary params) throws InterruptedException
    {
        int attempt = params == null ? 0 : params.getInt("attempt");
        try
        {
            if (pasture_lib.restoreAnimalsAboard(self, attempt) && attempt + 1 < pasture_lib.RESTORE_MAX_TRIES)
            {
                schedule(self, attempt + 1, pasture_lib.RESTORE_RETRY_SEC);
            }
        }
        catch (Throwable t)
        {
            LOG("pasture", "handleRestorePasture: ship " + self + " failed: " + t);
        }
        return SCRIPT_CONTINUE;
    }

    public static void schedule(obj_id ship, int attempt, float delay) throws InterruptedException
    {
        dictionary d = new dictionary();
        d.put("attempt", attempt);
        messageTo(ship, "handleRestorePasture", d, delay, false);
    }
}
