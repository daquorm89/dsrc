package script.library;

import script.dictionary;
import script.obj_id;
import script.prose_package;
import script.string_id;
import script.library.ai_lib;
import script.library.armor;
import script.library.jedi;
import script.library.loot;
import script.library.powerup;
import script.library.prose;
import script.library.static_item;
import script.library.sui;
import script.library.utils;
import script.library.weapons;
import script.library.xp;

import java.util.Vector;

public class slicing extends script.base_script
{
    public slicing()
    {
    }
    public static final string_id SID_SLICING_UNDERWAY = new string_id("slicing/slicing", "slicing_underway");
    public static final string_id SID_ALREADY_SLICING = new string_id("slicing/slicing", "already_slicing");
    public static final string_id SID_ALREADY_SLICED = new string_id("slicing/slicing", "already_sliced");
    public static final string_id SID_ALREADY_BEING_SLICED = new string_id("slicing/slicing", "already_being_sliced");
    public static final string_id SID_JEDI_NO_SLICE = new string_id("slicing/slicing", "jedi");
    public static final string_id SID_STATIC_ITEM = new string_id("slicing/slicing", "static");
    public static final string_id SID_CUSTOMIZATION = new string_id("slicing/slicing", "customization");
    public static final string_id SID_SLICE_NO_SKILL = new string_id("slicing/slicing", "no_skill");
    public static final string_id SID_NOT_ENOUGH_SKILL = new string_id("slicing/slicing", "not_enough_skill");
    public static final string_id SID_ALREADY_APPLIED = new string_id("slicing/slicing", "already_applied_sys");
    public static final string_id SID_CANT_APPLY = new string_id("slicing/slicing", "cant_apply_sys");
    public static final string_id SID_COST_TOO_HIGH = new string_id("slicing/slicing", "cost_too_high");
    public static final string_id SID_CANT_FIX = new string_id("slicing/slicing", "cant_fix");
    public static final string_id SID_FIX_SUCCESSFUL = new string_id("slicing/slicing", "fix_successful");
    public static final string_id SID_REQUIRES_COMPONENT = new string_id("slicing/slicing", "requires_component");
    public static final string_id SID_NO_COMPONENT_REQUIRED = new string_id("slicing/slicing", "no_comp_required");
    public static final string_id SID_MISSING_COMP = new string_id("slicing/slicing", "missing_comp");
    public static final String SLICE_WEAPON_TABLE = "datatables/smuggler/slice_weapon.iff";
    public static final String SLICE_ARMOR_TABLE = "datatables/smuggler/slice_armor.iff";
    public static void startSlicing(obj_id player, obj_id item, String callback, String type) throws InterruptedException
    {
        if (jedi.isLightsaber(item))
        {
            sendSystemMessage(player, SID_JEDI_NO_SLICE);
            return;
        }
        if (static_item.isStaticItem(item))
        {
            sendSystemMessage(player, SID_STATIC_ITEM);
            return;
        }
        if (utils.hasScriptVar(player, "slicing.slice_item"))
        {
            int started = utils.getIntScriptVar(player, SV + ".time");
            if (started > 0 && getGameTime() - started > SESSION_TIMEOUT_SEC)
            {
                clearSlicing(player);
            }
            else
            {
                sendSystemMessage(player, SID_ALREADY_SLICING);
                return;
            }
        }
        if (isPreCuType(type))
        {
            if (!canSlice(player, type))
            {
                sendSystemMessage(player, SID_SLICE_NO_SKILL);
                return;
            }
            string_id itemProblem = checkSliceableItem(player, item, type);
            if (itemProblem != null)
            {
                sendSystemMessage(player, itemProblem);
                return;
            }
            if (!isIdValid(findTool(player, TOOL_KNIFE)))
            {
                sendSystemMessage(player, SID_NO_KNIFE);
                return;
            }
        }
        if (hasObjVar(item, "slice_start"))
        {
            int slice_start = getIntObjVar(item, "slice_start");
            int delta = getGameTime() - slice_start;
            if (delta < 172800)
            {
                obj_id first_slicer = getObjIdObjVar(item, "sliced_by");
                if ((first_slicer != player) && (delta < 900))
                {
                    CustomerServiceLog("exploit", "Two players tried to double slice an item within 15 minutes, they may be exploiting.");
                    CustomerServiceLog("exploit", "--First Slicer: %TU", first_slicer);
                    CustomerServiceLog("exploit", "--Second Slicer: %TU", player);
                }
                sendSystemMessage(player, SID_ALREADY_BEING_SLICED);
                return;
            }
        }
        utils.setScriptVar(player, "slicing.slice_item", item);
        utils.setScriptVar(player, "slicing.type", type);
        utils.setScriptVar(player, "slicing.callback", callback);
        setObjVar(item, "slice_start", getGameTime());
        setObjVar(item, "sliced_by", player);
        attachScript(player, "player.player_slicing");
        if (isPreCuType(type))
        {
            startPreCuGame(player, item, type);
            return;
        }
        slicingQuery(player, item, type);
    }
    public static void clearSlicing(obj_id player) throws InterruptedException
    {
        obj_id slice_item = utils.getObjIdScriptVar(player, "slicing.slice_item");
        if (isIdValid(slice_item))
        {
            removeObjVar(slice_item, "slice_start");
            removeObjVar(slice_item, "sliced_by");
        }
        utils.removeScriptVar(player, "slicing.slice");
        utils.removeScriptVar(player, "slicing.type");
        utils.removeScriptVar(player, "slicing.cat");
        utils.removeScriptVar(player, "slicing.callback");
        utils.removeScriptVar(player, "slicing.slice_item");
        utils.removeScriptVar(player, "slicing.last_query");
        utils.removeScriptVar(player, "slicing.apply_status");
        utils.removeScriptVarTree(player, SV);
        detachScript(player, "player.player_slicing");
    }
    public static void slicingQuery(obj_id player, obj_id item, String query) throws InterruptedException
    {
        int skillMod = getSkillStatisticModifier(player, "slice_" + query);
        if (skillMod == 0)
        {
            if (hasSkill(player, "class_smuggler_phase1_novice"))
            {
                successSlicing(player, item, 0);
                return;
            }
            sendSystemMessage(player, SID_SLICE_NO_SKILL);
            clearSlicing(player);
            return;
        }
        String table = null;
        if (query.equals("weapon"))
        {
            table = SLICE_WEAPON_TABLE;
        }
        else if (query.equals("armor"))
        {
            table = SLICE_ARMOR_TABLE;
        }
        int rows = dataTableGetNumRows(table);
        Vector categories = new Vector();
        categories.setSize(0);
        String is_this_cat;
        for (int i = 0; i < rows; i++)
        {
            is_this_cat = dataTableGetString(table, i, "SLICE_CAT");
            if (is_this_cat == null || is_this_cat.equals(""))
            {
                continue;
            }
            int req = dataTableGetInt(table, i, "SKILL_REQ");
            if (skillMod >= req)
            {
                categories = utils.addElement(categories, "@slicing/slicing_" + query + ":" + is_this_cat);
            }
        }
        if (categories.size() == 0)
        {
            sendSystemMessage(player, SID_SLICE_NO_SKILL);
            clearSlicing(player);
            return;
        }
        sui.listbox(player, player, "@slicing/slicing:" + query, sui.OK_CANCEL, "@slicing/slicing:cat_title", categories, "handleSlicingCategory", true);
    }
    public static void handleSlicingCategory(obj_id player, int idx) throws InterruptedException
    {
        String query = utils.getStringScriptVar(player, "slicing.type");
        obj_id item = utils.getObjIdScriptVar(player, "slicing.slice_item");
        if (!isIdValid(item))
        {
            clearSlicing(player);
            return;
        }
        int skillMod = getSkillStatisticModifier(player, "slice_" + query);
        String table = null;
        if (query.equals("weapon"))
        {
            table = SLICE_WEAPON_TABLE;
        }
        else if (query.equals("armor"))
        {
            table = SLICE_ARMOR_TABLE;
        }
        int rows = dataTableGetNumRows(table);
        Vector slices = new Vector();
        slices.setSize(0);
        idx++;
        int cats = 0;
        int type = 0;
        utils.setScriptVar(player, "slicing.cat", idx);
        String is_this_cat;
        String slice;
        String stat;
        String color_prefix;
        String status_suffix;
        for (int i = 0; i < rows; i++)
        {
            is_this_cat = dataTableGetString(table, i, "SLICE_CAT");
            if (!is_this_cat.equals(""))
            {
                cats++;
                continue;
            }
            if (cats == idx)
            {
                slice = dataTableGetString(table, i, "SLICE");
                stat = dataTableGetString(table, i, "STAT");
                int req = dataTableGetInt(table, i, "SKILL_REQ");
                int rank = dataTableGetInt(table, i, "RANK");
                int cur_rank = getIntObjVar(item, "slice.rank." + stat);
                if (skillMod >= req)
                {
                    color_prefix = "";
                    status_suffix = "";
                    if (rank > cur_rank + 1)
                    {
                        color_prefix = "\\#888888";
                        status_suffix = "-- Can't be applied, yet.";
                    }
                    else if (rank <= cur_rank)
                    {
                        color_prefix = "\\#FFFFFF";
                        status_suffix = "-- Already applied.";
                    }
                    slices = utils.addElement(slices, "@slicing/slicing_" + query + ":" + slice + " " + color_prefix + status_suffix);
                    utils.setScriptVar(player, "slicing." + type, i);
                    type++;
                }
            }
        }
        sui.listbox(player, player, "@slicing/slicing:select_slice", sui.OK_CANCEL, "@slicing/slicing:slice_title", slices, "handleSlicingSelect", true);
    }
    public static void handleSlicingSelect(obj_id player, int idx) throws InterruptedException
    {
        int row = utils.getIntScriptVar(player, "slicing." + idx);
        obj_id item = utils.getObjIdScriptVar(player, "slicing.slice_item");
        String query = utils.getStringScriptVar(player, "slicing.type");
        String table = null;
        if (query.equals("weapon"))
        {
            table = SLICE_WEAPON_TABLE;
        }
        else if (query.equals("armor"))
        {
            table = SLICE_ARMOR_TABLE;
        }
        int skillMod = getSkillStatisticModifier(player, "slice_" + query);
        dictionary effect = dataTableGetRow(table, row);
        int req = effect.getInt("SKILL_REQ");
        if (skillMod < req)
        {
            sendSystemMessage(player, SID_NOT_ENOUGH_SKILL);
            clearSlicing(player);
            return;
        }
        String stat = effect.getString("STAT");
        int rank = effect.getInt("RANK");
        int cur_rank = getIntObjVar(item, "slice.rank." + stat);
        string_id apply_status = null;
        string_id apply_status_also = null;
        if (skillMod >= req)
        {
            if (rank > cur_rank + 1)
            {
                apply_status = new string_id("slicing/slicing", "cant_apply");
                dictionary effect_prereq = dataTableGetRow(table, row - 1);
                apply_status_also = new string_id("slicing/slicing_" + query, effect_prereq.getString("SLICE"));
                utils.setScriptVar(player, "slicing.apply_status", 1);
            }
            else if (rank <= cur_rank)
            {
                apply_status = new string_id("slicing/slicing", "already_applied");
                utils.setScriptVar(player, "slicing.apply_status", 2);
            }
            else 
            {
                apply_status = new string_id("slicing/slicing", "can_apply");
                utils.setScriptVar(player, "slicing.apply_status", 3);
            }
        }
        int new_cust = effect.getInt("COST");
        int cust = getIntObjVar(item, "slice.customized");
        if (cust + new_cust > 100)
        {
            apply_status = new string_id("slicing/slicing", "cant_apply_cost");
            utils.setScriptVar(player, "slicing.apply_status", 5);
        }
        String component = effect.getString("COMPONENT");
        String component_path = "object/tangible/smuggler/" + component + ".iff";
        string_id component_sid = new string_id("smuggler/items", component);
        if (component != null && !component.equals(""))
        {
            obj_id pInv = utils.getInventoryContainer(player);
            obj_id[] contents = utils.getContents(pInv, true);
            int found = 0;
            for (obj_id content : contents) {
                if (getTemplateName(content).equals(component_path)) {
                    found = 1;
                    break;
                }
            }
            if (found == 0)
            {
                apply_status = new string_id("slicing/slicing", "missing_component");
                utils.setScriptVar(player, "slicing.apply_status", 4);
                utils.setScriptVar(player, "slicing.comp", component);
            }
        }
        utils.setScriptVar(player, "slicing.row", row);
        prose_package slice_attributes[] = new prose_package[4];
        slice_attributes[0] = prose.getPackage(SID_CUSTOMIZATION, effect.getInt("COST"));
        slice_attributes[1] = prose.getPackage(new string_id("slicing/slicing_" + query, effect.getString("STAT") + "_attrib"), effect.getInt("AMOUNT"));
        if (component == null || component.equals(""))
        {
            slice_attributes[2] = prose.getPackage(SID_NO_COMPONENT_REQUIRED);
        }
        else 
        {
            slice_attributes[2] = prose.getPackage(SID_REQUIRES_COMPONENT, component_sid);
        }
        slice_attributes[3] = prose.getPackage(apply_status, apply_status_also);
        sui.listbox(player, player, "@slicing/slicing_" + query + ":" + effect.getString("STAT") + "_detail", sui.OK_CANCEL, "@slicing/slicing:slice_attributes", slice_attributes, "handleApplySlice", true, false);
    }
    public static void handleApplySlice(obj_id player, int idx) throws InterruptedException
    {
        int row = utils.getIntScriptVar(player, "slicing.row");
        String query = utils.getStringScriptVar(player, "slicing.type");
        String table = null;
        if (query.equals("weapon"))
        {
            table = SLICE_WEAPON_TABLE;
        }
        else if (query.equals("armor"))
        {
            table = SLICE_ARMOR_TABLE;
        }
        obj_id item = utils.getObjIdScriptVar(player, "slicing.slice_item");
        int apply_status = utils.getIntScriptVar(player, "slicing.apply_status");
        if (apply_status == 2)
        {
            sendSystemMessage(player, SID_ALREADY_APPLIED);
            clearSlicing(player);
            return;
        }
        else if (apply_status == 1)
        {
            dictionary effect_prereq = dataTableGetRow(table, row - 1);
            string_id apply_status_also = new string_id("slicing/slicing_" + query, effect_prereq.getString("SLICE"));
            prose_package pp = prose.getPackage(SID_CANT_APPLY, apply_status_also);
            sendSystemMessageProse(player, pp);
            clearSlicing(player);
            return;
        }
        else if (apply_status == 4)
        {
            String comp = utils.getStringScriptVar(player, "slicing.comp");
            string_id missing_comp = new string_id("smuggler/items", comp);
            prose_package pp = prose.getPackage(SID_MISSING_COMP, missing_comp);
            sendSystemMessageProse(player, pp);
            clearSlicing(player);
            return;
        }
        dictionary effect = dataTableGetRow(table, row);
        int new_cust = effect.getInt("COST");
        int cust = getIntObjVar(item, "slice.customized");
        if (cust + new_cust > 100)
        {
            sendSystemMessage(player, SID_COST_TOO_HIGH);
            clearSlicing(player);
            return;
        }
        String component = effect.getString("COMPONENT");
        String component_path = "object/tangible/smuggler/" + component + ".iff";
        if (component != null && !component.equals(""))
        {
            obj_id pInv = utils.getInventoryContainer(player);
            obj_id[] contents = utils.getContents(pInv, true);
            int found = 0;
            for (obj_id content : contents) {
                if (getTemplateName(content).equals(component_path)) {
                    found = 1;
                    consumeComponent(content);
                    break;
                }
            }
            if (found == 0)
            {
                String comp = utils.getStringScriptVar(player, "slicing.comp");
                string_id missing_comp = new string_id("smuggler/items", comp);
                prose_package pp = prose.getPackage(SID_MISSING_COMP, missing_comp);
                sendSystemMessageProse(player, pp);
                clearSlicing(player);
                return;
            }
        }
        successSlicing(player, item, row);
    }
    public static void successSlicing(obj_id player, obj_id item, int row) throws InterruptedException
    {
        String callback = utils.getStringScriptVar(player, "slicing.callback");
        dictionary outp = new dictionary();
        outp.put("success", 1);
        outp.put("row", row);
        outp.put("player", player);
        messageTo(item, callback, outp, 0.0f, true);
        clearSlicing(player);
    }
    public static void failSlicing(obj_id player, obj_id item) throws InterruptedException
    {
        String callback = utils.getStringScriptVar(player, "slicing.callback");
        dictionary outp = new dictionary();
        outp.put("success", 0);
        outp.put("player", player);
        messageTo(item, callback, outp, 0.0f, true);
        clearSlicing(player);
    }
    // =====================================================================
    // Pre-CU slicing (P28). Restores the Pre-CU Smuggler slicing game for
    // containers, keypads, mission terminals, HQ security terminals,
    // weapons and armor. Gate = the Pre-CU skill commands; the NGE class
    // skill stays accepted as a fallback. All numbers are tuning knobs.
    // =====================================================================
    public static final String BUILD_TAG = "PRECU-SLICE-1";
    public static final boolean PRECU_SLICING_ENABLED = true;
    public static final String NGE_SMUGGLER_SKILL = "class_smuggler_phase1_novice";
    public static final String TOOL_KNIFE = "object/tangible/slicing/slicing_laser_knife.iff";
    public static final String TOOL_ANALYZER = "object/tangible/slicing/slicing_flow_analyzer.iff";
    public static final String TOOL_CLAMP = "object/tangible/slicing/slicing_molecular_clamp.iff";
    public static final string_id SID_NO_KNIFE = new string_id("slicing/slicing", "no_knife");
    public static final string_id SID_NO_NODE = new string_id("slicing/slicing", "no_node");
    public static final string_id SID_NO_CLAMP = new string_id("slicing/slicing", "no_clamp");
    public static final string_id SID_USED_KNIFE = new string_id("slicing/slicing", "used_knife");
    public static final string_id SID_USED_UP_KNIFE = new string_id("slicing/slicing", "used_up_knife");
    public static final string_id SID_USED_NODE = new string_id("slicing/slicing", "used_node");
    public static final string_id SID_USED_CLAMP = new string_id("slicing/slicing", "used_clamp");
    public static final string_id SID_BLUE_CUT = new string_id("slicing/slicing", "blue_cable_cut");
    public static final string_id SID_RED_CUT = new string_id("slicing/slicing", "red_cable_cut");
    public static final string_id SID_FAIL_WEAPON = new string_id("slicing/slicing", "fail_weapon");
    public static final string_id SID_FAIL_ARMOR = new string_id("slicing/slicing", "fail_armor");
    public static final string_id SID_WEAPON_AT_MAX = new string_id("slicing/slicing", "weapon_at_max");
    public static final string_id SID_NOT_ARMOR = new string_id("slicing/slicing", "not_armor");
    public static final string_id SID_NOT_IN_INV = new string_id("slicing/slicing", "not_in_inv");
    public static final string_id SID_DAM_MOD = new string_id("slicing/slicing", "dam_mod");
    public static final string_id SID_SPD_MOD = new string_id("slicing/slicing", "spd_mod");
    public static final string_id SID_EFF_MOD = new string_id("slicing/slicing", "eff_mod");
    public static final string_id SID_ENC_MOD = new string_id("slicing/slicing", "enc_mod");
    public static final string_id SID_TITLE = new string_id("slicing/slicing", "title");
    public static final String SV = "slicing.precu";
    public static final int SESSION_TIMEOUT_SEC = 180;
    // Flow Analyzer reading is right ANALYZER_BASE_ACCURACY + per slicing level, capped.
    public static final int ANALYZER_BASE_ACCURACY = 65;
    public static final int ANALYZER_ACCURACY_PER_LEVEL = 5;
    public static final int ANALYZER_MAX_ACCURACY = 95;
    // Slicing XP on success (xp type "slicing", trains Slicing I-IV).
    public static final int XP_CONTAINER = 250;
    public static final int XP_KEYPAD = 250;
    public static final int XP_TERMINAL = 200;
    public static final int XP_HQ_SECURITY = 500;
    public static final int XP_WEAPON = 600;
    public static final int XP_ARMOR = 700;
    // Weapon slice: damage or speed, percent ranges per Pre-CU level (Core3 reference).
    public static final int[] WEAPON_SLICE_MIN = {10, 15, 20};
    public static final int[] WEAPON_SLICE_MAX = {25, 30, 35};
    // Armor slice: effectiveness (general protection) or encumbrance (armor penalties).
    public static final int[] ARMOR_EFF_MIN = {10, 15, 20};
    public static final int[] ARMOR_EFF_MAX = {20, 25, 30};
    public static final int[] ARMOR_ENC_MIN = {15, 25, 30};
    public static final int[] ARMOR_ENC_MAX = {30, 40, 50};
    // Locked containers dropped by humanoid NPCs.
    public static final boolean LOCKED_CRATE_DROPS_ENABLED = true;
    public static final int LOCKED_CRATE_DROP_PERCENT = 4;
    public static final String LOCKED_CRATE_TEMPLATE = "object/tangible/container/loot/player_loot_crate.iff";
    public static final String LOCKED_CRATE_DEFAULT_TABLE = "npc/npc_1_10";
    public static final int LOCKED_CRATE_ITEMS_MIN = 2;
    public static final int LOCKED_CRATE_ITEMS_MAX = 4;

    // Pre-CU slicing level: 0 none, 1 Smuggler novice, 2 Slicing I, 3 Slicing II,
    // 4 Slicing III, 5 Slicing IV, 6 Master Smuggler.
    public static int getPreCuSlicingLevel(obj_id player) throws InterruptedException
    {
        if (!isIdValid(player))
        {
            return 0;
        }
        if (hasSkill(player, "combat_smuggler_master"))
        {
            return 6;
        }
        if (hasSkill(player, "combat_smuggler_slicing_04"))
        {
            return 5;
        }
        if (hasSkill(player, "combat_smuggler_slicing_03"))
        {
            return 4;
        }
        if (hasSkill(player, "combat_smuggler_slicing_02"))
        {
            return 3;
        }
        if (hasSkill(player, "combat_smuggler_slicing_01"))
        {
            return 2;
        }
        if (hasSkill(player, "combat_smuggler_novice"))
        {
            return 1;
        }
        return 0;
    }
    public static String getRequiredCommand(String type) throws InterruptedException
    {
        if (type == null)
        {
            return null;
        }
        switch (type)
        {
            case "container":
                return "slice_containers";
            case "keypad":
            case "terminal":
            case "hq_security":
                return "slice_terminals";
            case "weapon":
                return "slice_weaponsbasic";
            case "armor":
                return "slice_armor";
        }
        return null;
    }
    public static boolean canSlice(obj_id player, String type) throws InterruptedException
    {
        if (!isIdValid(player))
        {
            return false;
        }
        String cmd = getRequiredCommand(type);
        if (cmd != null && hasCommand(player, cmd))
        {
            return true;
        }
        return hasSkill(player, NGE_SMUGGLER_SKILL);
    }
    public static boolean isPreCuType(String type) throws InterruptedException
    {
        return PRECU_SLICING_ENABLED && getRequiredCommand(type) != null;
    }
    public static int levelIndex(obj_id player) throws InterruptedException
    {
        int lvl = getPreCuSlicingLevel(player);
        if (lvl >= 6)
        {
            return 2;
        }
        if (lvl >= 5)
        {
            return 1;
        }
        return 0;
    }
    public static obj_id findTool(obj_id player, String template) throws InterruptedException
    {
        obj_id pInv = utils.getInventoryContainer(player);
        if (!isIdValid(pInv))
        {
            return null;
        }
        obj_id[] contents = utils.getContents(pInv, true);
        if (contents == null)
        {
            return null;
        }
        for (obj_id content : contents)
        {
            if (isIdValid(content) && template.equals(getTemplateName(content)))
            {
                return content;
            }
        }
        return null;
    }
    public static void useTool(obj_id player, obj_id tool, string_id usedMsg, string_id usedUpMsg) throws InterruptedException
    {
        int count = getCount(tool);
        if (count > 1)
        {
            setCount(tool, count - 1);
            sendSystemMessage(player, usedMsg);
        }
        else
        {
            destroyObject(tool);
            sendSystemMessage(player, usedUpMsg != null ? usedUpMsg : usedMsg);
        }
    }
    public static boolean isSlicerItemOwner(obj_id player, obj_id item) throws InterruptedException
    {
        return isIdValid(item) && utils.getContainingPlayer(item) == player && !utils.isEquipped(item);
    }
    public static boolean isItemAlreadySliced(obj_id item) throws InterruptedException
    {
        return hasObjVar(item, "precu_slice.type") || hasObjVar(item, "precu_slice.failed") || hasObjVar(item, "slice.customized");
    }
    // Returns null when the item may be sliced, else the message to show.
    public static string_id checkSliceableItem(obj_id player, obj_id item, String type) throws InterruptedException
    {
        if (type.equals("weapon"))
        {
            if (!isGameObjectTypeOf(getGameObjectType(item), GOT_weapon))
            {
                return SID_SLICE_NO_SKILL;
            }
            if (!isSlicerItemOwner(player, item))
            {
                return SID_NOT_IN_INV;
            }
            if (isItemAlreadySliced(item))
            {
                return SID_ALREADY_SLICED;
            }
            if (powerup.hasPowerUpInstalled(item))
            {
                return SID_STATIC_ITEM;
            }
            int master = weapons.isCoredWeapon(item) ? weapons.CORED_WEAPON_CONVERSION_VERSION : weapons.CONVERSION_VERSION;
            if (getConversionId(item) != master)
            {
                return SID_STATIC_ITEM;
            }
        }
        else if (type.equals("armor"))
        {
            if (!armor.isFinalArmor(item) || armor.isPsg(item) || !armor.isValidArmor(item) || !hasObjVar(item, armor.OBJVAR_ARMOR_BASE + "." + armor.OBJVAR_GENERAL_PROTECTION))
            {
                return SID_NOT_ARMOR;
            }
            if (static_item.isDynamicItem(item))
            {
                return SID_STATIC_ITEM;
            }
            if (!isSlicerItemOwner(player, item))
            {
                return SID_NOT_IN_INV;
            }
            if (isItemAlreadySliced(item))
            {
                return SID_ALREADY_SLICED;
            }
        }
        return null;
    }
    // Cheap check for showing the "Slice" radial on weapons / armor.
    public static boolean showSliceRadial(obj_id player, obj_id item, String type) throws InterruptedException
    {
        if (!PRECU_SLICING_ENABLED || !isIdValid(player) || !isPlayer(player))
        {
            return false;
        }
        if (!canSlice(player, type) || jedi.isLightsaber(item) || static_item.isStaticItem(item))
        {
            return false;
        }
        return isSlicerItemOwner(player, item) && !isItemAlreadySliced(item);
    }
    public static void startPreCuGame(obj_id player, obj_id item, String type) throws InterruptedException
    {
        utils.removeScriptVarTree(player, SV);
        utils.setScriptVar(player, SV + ".time", getGameTime());
        utils.setScriptVar(player, SV + ".stage", 0);
        utils.setScriptVar(player, SV + ".cable", rand(0, 1));
        utils.setScriptVar(player, SV + ".prompt", type + "_0");
        LOG("slicing", "[" + BUILD_TAG + "] start " + type + " player=" + player + " item=" + item + " level=" + getPreCuSlicingLevel(player));
        showPreCuMenu(player);
    }
    public static void showPreCuMenu(obj_id player) throws InterruptedException
    {
        int stage = utils.getIntScriptVar(player, SV + ".stage");
        String prompt = utils.getStringScriptVar(player, SV + ".prompt");
        boolean toolUsed = utils.hasScriptVar(player, SV + ".tool");
        Vector opts = new Vector();
        Vector labels = new Vector();
        if (stage == 0)
        {
            opts.add("blue");
            labels.add("@slicing/slicing:blue_cable");
            opts.add("red");
            labels.add("@slicing/slicing:red_cable");
            if (!toolUsed)
            {
                opts.add("clamp");
                labels.add("@slicing/slicing:use_clamp");
                opts.add("node");
                labels.add("@slicing/slicing:use_analyzer");
            }
        }
        else
        {
            int cut = utils.getIntScriptVar(player, SV + ".cut");
            if (cut == 0)
            {
                opts.add("red");
                labels.add("@slicing/slicing:red_cable");
            }
            else
            {
                opts.add("blue");
                labels.add("@slicing/slicing:blue_cable");
            }
        }
        String[] optArray = new String[opts.size()];
        opts.toArray(optArray);
        utils.setScriptVar(player, SV + ".opts", optArray);
        String[] labelArray = new String[labels.size()];
        labels.toArray(labelArray);
        sui.listbox(player, player, "@slicing/slicing:" + prompt, sui.OK_CANCEL, "@slicing/slicing:title", labelArray, "handlePreCuSlice", true);
    }
    public static void handlePreCuChoice(obj_id player, int idx) throws InterruptedException
    {
        obj_id item = utils.getObjIdScriptVar(player, "slicing.slice_item");
        String type = utils.getStringScriptVar(player, "slicing.type");
        String[] opts = utils.getStringArrayScriptVar(player, SV + ".opts");
        if (!isIdValid(item) || !exists(item) || type == null || opts == null || idx < 0 || idx >= opts.length)
        {
            clearSlicing(player);
            return;
        }
        if ((type.equals("weapon") || type.equals("armor")) && !isSlicerItemOwner(player, item))
        {
            sendSystemMessage(player, SID_NOT_IN_INV);
            clearSlicing(player);
            return;
        }
        String choice = opts[idx];
        int cable = utils.getIntScriptVar(player, SV + ".cable");
        int stage = utils.getIntScriptVar(player, SV + ".stage");
        if (choice.equals("clamp"))
        {
            obj_id clamp = findTool(player, TOOL_CLAMP);
            if (!isIdValid(clamp))
            {
                sendSystemMessage(player, SID_NO_CLAMP);
                showPreCuMenu(player);
                return;
            }
            useTool(player, clamp, SID_USED_CLAMP, null);
            utils.setScriptVar(player, SV + ".tool", 1);
            utils.setScriptVar(player, SV + ".prompt", type + "_clamp_" + cable);
            showPreCuMenu(player);
            return;
        }
        if (choice.equals("node"))
        {
            obj_id node = findTool(player, TOOL_ANALYZER);
            if (!isIdValid(node))
            {
                sendSystemMessage(player, SID_NO_NODE);
                showPreCuMenu(player);
                return;
            }
            useTool(player, node, SID_USED_NODE, null);
            int accuracy = ANALYZER_BASE_ACCURACY + ANALYZER_ACCURACY_PER_LEVEL * getPreCuSlicingLevel(player);
            if (accuracy > ANALYZER_MAX_ACCURACY)
            {
                accuracy = ANALYZER_MAX_ACCURACY;
            }
            int reading = (rand(1, 100) <= accuracy) ? cable : 1 - cable;
            utils.setScriptVar(player, SV + ".tool", 1);
            utils.setScriptVar(player, SV + ".prompt", type + "_analyze_" + reading);
            showPreCuMenu(player);
            return;
        }
        int cut = choice.equals("blue") ? 0 : 1;
        obj_id knife = findTool(player, TOOL_KNIFE);
        if (!isIdValid(knife))
        {
            sendSystemMessage(player, SID_NO_KNIFE);
            clearSlicing(player);
            return;
        }
        useTool(player, knife, SID_USED_KNIFE, SID_USED_UP_KNIFE);
        if (stage == 0)
        {
            if (cut != cable)
            {
                preCuFail(player, item, type);
                return;
            }
            sendSystemMessage(player, cut == 0 ? SID_BLUE_CUT : SID_RED_CUT);
            utils.setScriptVar(player, SV + ".stage", 1);
            utils.setScriptVar(player, SV + ".cut", cut);
            utils.setScriptVar(player, SV + ".prompt", type + "_1");
            showPreCuMenu(player);
            return;
        }
        preCuSuccess(player, item, type);
    }
    public static int getSliceXp(String type) throws InterruptedException
    {
        switch (type)
        {
            case "container":
                return XP_CONTAINER;
            case "keypad":
                return XP_KEYPAD;
            case "terminal":
                return XP_TERMINAL;
            case "hq_security":
                return XP_HQ_SECURITY;
            case "weapon":
                return XP_WEAPON;
            case "armor":
                return XP_ARMOR;
        }
        return 0;
    }
    public static void preCuSuccess(obj_id player, obj_id item, String type) throws InterruptedException
    {
        LOG("slicing", "[" + BUILD_TAG + "] success " + type + " player=" + player + " item=" + item);
        int xpAmount = getSliceXp(type);
        if (xpAmount > 0)
        {
            xp.grant(player, xp.SLICING, xpAmount, true);
        }
        if (type.equals("weapon"))
        {
            applyPreCuWeaponSlice(player, item);
            clearSlicing(player);
            return;
        }
        if (type.equals("armor"))
        {
            applyPreCuArmorSlice(player, item);
            clearSlicing(player);
            return;
        }
        successSlicing(player, item, 0);
    }
    public static void preCuFail(obj_id player, obj_id item, String type) throws InterruptedException
    {
        LOG("slicing", "[" + BUILD_TAG + "] fail " + type + " player=" + player + " item=" + item);
        if (type.equals("weapon") || type.equals("armor"))
        {
            setObjVar(item, "precu_slice.failed", 1);
            sendSystemMessage(player, type.equals("weapon") ? SID_FAIL_WEAPON : SID_FAIL_ARMOR);
            sendDirtyAttributesNotification(item);
            clearSlicing(player);
            return;
        }
        failSlicing(player, item);
    }
    public static void markSliced(obj_id player, obj_id item, String kind, int pct) throws InterruptedException
    {
        setObjVar(item, "precu_slice.type", kind);
        setObjVar(item, "precu_slice.amount", pct);
        setObjVar(item, "precu_slice.by", player);
        setObjVar(item, "precu_slice.time", getCalendarTime());
        sendDirtyAttributesNotification(item);
    }
    public static void applyPreCuWeaponSlice(obj_id player, obj_id weapon) throws InterruptedException
    {
        int li = levelIndex(player);
        int pct = rand(WEAPON_SLICE_MIN[li], WEAPON_SLICE_MAX[li]);
        float f = pct / 100.0f;
        if (rand(0, 1) == 0)
        {
            int minDam = getWeaponMinDamage(weapon);
            int maxDam = getWeaponMaxDamage(weapon);
            setWeaponMinDamage(weapon, Math.round(minDam * (1.0f + f)));
            setWeaponMaxDamage(weapon, Math.round(maxDam * (1.0f + f)));
            markSliced(player, weapon, "damage", pct);
            sendSystemMessageProse(player, prose.getPackage(SID_DAM_MOD, pct));
            LOG("slicing", "[" + BUILD_TAG + "] weapon " + weapon + " damage +" + pct + "% min " + minDam + "->" + getWeaponMinDamage(weapon) + " max " + maxDam + "->" + getWeaponMaxDamage(weapon));
        }
        else
        {
            float spd = getWeaponAttackSpeed(weapon);
            float newSpd = spd * (1.0f - f);
            if (newSpd < 0.1f)
            {
                newSpd = 0.1f;
            }
            setWeaponAttackSpeed(weapon, newSpd);
            markSliced(player, weapon, "speed", pct);
            sendSystemMessageProse(player, prose.getPackage(SID_SPD_MOD, pct));
            LOG("slicing", "[" + BUILD_TAG + "] weapon " + weapon + " speed +" + pct + "% " + spd + "->" + newSpd);
        }
        weapons.setWeaponData(weapon);
    }
    public static void applyPreCuArmorSlice(obj_id player, obj_id item) throws InterruptedException
    {
        int li = levelIndex(player);
        if (rand(0, 1) == 0)
        {
            int pct = rand(ARMOR_EFF_MIN[li], ARMOR_EFF_MAX[li]);
            String objvar = armor.OBJVAR_ARMOR_BASE + "." + armor.OBJVAR_GENERAL_PROTECTION;
            float v = getFloatObjVar(item, objvar);
            int row = armor.getArmorDatatableRow(item);
            float lo = armor.getAbsoluteArmorAttribute(0.0f, row, armor.DATATABLE_MIN_GEN_PROT_COL);
            float hi = armor.getAbsoluteArmorAttribute(1.0f, row, armor.DATATABLE_MIN_GEN_PROT_COL);
            float newV = v;
            if (row >= 0 && lo != Float.MIN_VALUE && hi != Float.MIN_VALUE && hi > lo)
            {
                float abs = v * (hi - lo) + lo;
                newV = ((abs * (1.0f + pct / 100.0f)) - lo) / (hi - lo);
            }
            else
            {
                newV = v * (1.0f + pct / 100.0f);
            }
            if (newV > armor.MAX_ARMOR_RANGE)
            {
                newV = armor.MAX_ARMOR_RANGE;
            }
            setObjVar(item, objvar, newV);
            armor.clearCachedData(item);
            markSliced(player, item, "effectiveness", pct);
            sendSystemMessageProse(player, prose.getPackage(SID_EFF_MOD, pct));
            LOG("slicing", "[" + BUILD_TAG + "] armor " + item + " effectiveness +" + pct + "% gp " + v + "->" + newV);
        }
        else
        {
            int pct = rand(ARMOR_ENC_MIN[li], ARMOR_ENC_MAX[li]);
            setObjVar(item, "precu_slice.encumbrance", pct);
            armor.clearCachedData(item);
            markSliced(player, item, "encumbrance", pct);
            sendSystemMessageProse(player, prose.getPackage(SID_ENC_MOD, pct));
            LOG("slicing", "[" + BUILD_TAG + "] armor " + item + " encumbrance -" + pct + "%");
        }
    }
    // Examine line text for a sliced weapon or armor piece, or null.
    public static String getSliceAttributeText(obj_id item) throws InterruptedException
    {
        if (hasObjVar(item, "precu_slice.failed"))
        {
            return "@obj_attr_n:broken";
        }
        if (!hasObjVar(item, "precu_slice.type"))
        {
            return null;
        }
        String kind = getStringObjVar(item, "precu_slice.type");
        int pct = getIntObjVar(item, "precu_slice.amount");
        switch (kind)
        {
            case "damage":
                return "+" + pct + "% damage";
            case "speed":
                return "+" + pct + "% speed";
            case "effectiveness":
                return "+" + pct + "% effectiveness";
            case "encumbrance":
                return "-" + pct + "% encumbrance";
        }
        return kind;
    }
    // Multiplier for armor penalties (encumbrance slice), 1.0 when not sliced.
    public static float getEncumbranceFactor(obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !hasObjVar(item, "precu_slice.encumbrance"))
        {
            return 1.0f;
        }
        int pct = getIntObjVar(item, "precu_slice.encumbrance");
        if (pct <= 0)
        {
            return 1.0f;
        }
        if (pct > 100)
        {
            pct = 100;
        }
        return 1.0f - (pct / 100.0f);
    }
    // Chance for a humanoid NPC to carry a locked container (called from loot.addLoot).
    public static boolean addLockedCrateDrop(obj_id npc) throws InterruptedException
    {
        if (!PRECU_SLICING_ENABLED || !LOCKED_CRATE_DROPS_ENABLED || !isIdValid(npc))
        {
            return false;
        }
        if (ai_lib.aiGetNiche(npc) != NICHE_NPC || hasObjVar(npc, "storytellerid"))
        {
            return false;
        }
        if (rand(1, 100) > LOCKED_CRATE_DROP_PERCENT)
        {
            return false;
        }
        obj_id inv = utils.getInventoryContainer(npc);
        if (!isIdValid(inv))
        {
            return false;
        }
        obj_id crate = createObject(LOCKED_CRATE_TEMPLATE, inv, "");
        if (!isIdValid(crate))
        {
            return false;
        }
        String table = hasObjVar(npc, "loot.lootTable") ? getStringObjVar(npc, "loot.lootTable") : LOCKED_CRATE_DEFAULT_TABLE;
        if (table == null || table.equals(""))
        {
            table = LOCKED_CRATE_DEFAULT_TABLE;
        }
        setObjVar(crate, "precu_slice.crate.table", table);
        setObjVar(crate, "precu_slice.crate.level", getIntObjVar(npc, "intCombatDifficulty"));
        LOG("slicing", "[" + BUILD_TAG + "] locked crate " + crate + " dropped by " + npc + " table=" + table);
        return true;
    }
    // Fill the slicer's inventory with the contents of a successfully sliced locked crate.
    public static void openLockedCrate(obj_id crate, obj_id player) throws InterruptedException
    {
        obj_id inv = utils.getInventoryContainer(player);
        if (!isIdValid(inv))
        {
            return;
        }
        String table = hasObjVar(crate, "precu_slice.crate.table") ? getStringObjVar(crate, "precu_slice.crate.table") : LOCKED_CRATE_DEFAULT_TABLE;
        int level = hasObjVar(crate, "precu_slice.crate.level") ? getIntObjVar(crate, "precu_slice.crate.level") : 10;
        if (level < 1)
        {
            level = 1;
        }
        int items = rand(LOCKED_CRATE_ITEMS_MIN, LOCKED_CRATE_ITEMS_MAX);
        boolean made = false;
        try
        {
            made = loot.makeLootInContainer(inv, table, items, level);
        }
        catch (Throwable t)
        {
            LOG("slicing", "[" + BUILD_TAG + "] crate loot table " + table + " failed: " + t);
        }
        if (!made && !table.equals(LOCKED_CRATE_DEFAULT_TABLE))
        {
            loot.makeLootInContainer(inv, LOCKED_CRATE_DEFAULT_TABLE, items, level);
        }
        LOG("slicing", "[" + BUILD_TAG + "] crate " + crate + " opened by " + player + " table=" + table + " items=" + items + " level=" + level);
    }
    public static void consumeComponent(obj_id item) throws InterruptedException
    {
        int count = getCount(item);
        count--;
        if (count <= 0)
        {
            destroyObject(item);
        }
        else 
        {
            setCount(item, count);
        }
    }
}
