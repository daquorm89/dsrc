package script.cybernetic;

import script.dictionary;
import script.library.sui;
import script.library.utils;
import script.obj_id;

/**
 * P24 - Handlers for player cybernetic surgery. Attached on demand to the patient (part choice)
 * and the surgeon (confirmation, surgery timer). All rules live in script.library.cybernetic_surgery.
 */
public class cybernetic_surgery extends script.base_script
{
    public cybernetic_surgery()
    {
    }

    /** On the patient: the patient picked a part (or cancelled). */
    public int handleCyberSurgeryPatientChoice(obj_id self, dictionary params) throws InterruptedException
    {
        String sv = script.library.cybernetic_surgery.SV_PATIENT;
        obj_id surgeon = utils.getObjIdScriptVar(self, sv + ".surgeon");
        obj_id[] parts = utils.getObjIdArrayScriptVar(self, sv + ".parts");
        utils.removeScriptVarTree(self, sv);
        sui.removePid(self, sv);
        if (params == null || !isIdValid(surgeon) || parts == null || parts.length == 0)
        {
            return SCRIPT_CONTINUE;
        }
        int bp = sui.getIntButtonPressed(params);
        int idx = sui.getListboxSelectedRow(params);
        if (bp != sui.BP_OK || idx < 0 || idx >= parts.length)
        {
            script.library.cybernetic_surgery.msg(surgeon, getPlayerName(self) + " declined the cybernetic surgery.");
            return SCRIPT_CONTINUE;
        }
        script.library.cybernetic_surgery.patientChose(self, surgeon, parts[idx]);
        return SCRIPT_CONTINUE;
    }

    /** On the surgeon: OK starts the surgery, Cancel drops it. */
    public int handleCyberSurgeryConfirm(obj_id self, dictionary params) throws InterruptedException
    {
        String sv = script.library.cybernetic_surgery.SV_SURGEON;
        sui.removePid(self, sv);
        obj_id patient = utils.getObjIdScriptVar(self, sv + ".patient");
        if (params == null || sui.getIntButtonPressed(params) != sui.BP_OK)
        {
            if (!script.library.cybernetic_surgery.isBusy(self))
            {
                utils.removeScriptVarTree(self, sv);
            }
            if (isIdValid(patient))
            {
                script.library.cybernetic_surgery.msg(patient, getPlayerName(self) + " decided not to perform the surgery.");
            }
            return SCRIPT_CONTINUE;
        }
        script.library.cybernetic_surgery.startSurgery(self);
        return SCRIPT_CONTINUE;
    }

    /** On the surgeon: the surgery timer ran out. */
    public int handleCyberSurgeryComplete(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null)
        {
            return SCRIPT_CONTINUE;
        }
        obj_id patient = params.getObjId("patient");
        obj_id item = params.getObjId("item");
        int started = params.getInt("started");
        script.library.cybernetic_surgery.finishSurgery(self, patient, item, started);
        return SCRIPT_CONTINUE;
    }
}
