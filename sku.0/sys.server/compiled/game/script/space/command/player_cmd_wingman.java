package script.space.command;

import script.dictionary;
import script.library.space_transition;
import script.library.space_utils;
import script.obj_id;

public class player_cmd_wingman extends script.base_script
{
    public player_cmd_wingman()
    {
    }
    public static final float CHECK_SECONDS = 10.0f;
    public int OnAttach(obj_id self) throws InterruptedException
    {
        messageTo(self, "wingmanCheck", new dictionary(), CHECK_SECONDS, false);
        return SCRIPT_CONTINUE;
    }
    public int wingmanCheck(obj_id self, dictionary params) throws InterruptedException
    {
        if (hasObjVar(self, "intCleaningUp"))
        {
            return SCRIPT_CONTINUE;
        }
        boolean keep = false;
        obj_id commander = getObjIdObjVar(self, "commanderPlayer");
        if (isIdValid(commander) && exists(commander))
        {
            obj_id commanderShip = space_transition.getContainingShip(commander);
            keep = isIdValid(commanderShip) && exists(commanderShip);
        }
        if (!keep)
        {
            setObjVar(self, "intCleaningUp", 1);
            setObjVar(self, "evacuate", 1);
            destroyObject(self);
            return SCRIPT_CONTINUE;
        }
        messageTo(self, "wingmanCheck", new dictionary(), CHECK_SECONDS, false);
        return SCRIPT_CONTINUE;
    }
    public int OnDestroy(obj_id self) throws InterruptedException
    {
        if (hasObjVar(self, "evacuate") || hasObjVar(self, "intCleaningUp"))
        {
            return SCRIPT_CONTINUE;
        }
        if (hasObjVar(self, "commanderPlayer"))
        {
            dictionary outparams = new dictionary();
            outparams.put("deadFighterId", self);
            space_utils.notifyObject(getObjIdObjVar(self, "commanderPlayer"), "wingmanDestroyed", outparams);
        }
        return SCRIPT_CONTINUE;
    }
}
