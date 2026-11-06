package script.npc.pet_deed;

import script.*;
import script.library.beast_lib;
import script.library.create;
import script.library.hue;
import script.library.incubator;
import script.library.pet_lib;
import script.library.sui;
import script.library.utils;

public class pet_deed extends script.base_script
{
    public pet_deed()
    {
    }
    public static final String MENU_FILE = "pet/pet_menu";
    public static final string_id SID_UNPACK = new string_id(MENU_FILE, "menu_unpack");
    // PRECU-DEED-1: creature pet deeds (bio-engineered or otherwise) can be unpacked into a datapad control device.
    // NGE removed this; only "convert to DNA" (Beast Master) was left. Switch off to get the NGE behaviour back.
    public static final boolean PRECU_DEED_UNPACK_ENABLED = true;
    // Growth stage a freshly unpacked pet starts at (1 = baby ... 10 = adult), same as a hatched egg.
    public static final int UNPACK_GROWTH_STAGE = 1;
    public static final string_id SID_CONVERT_PET_ITEM_TO_DNA = new string_id("incubator", "convert_pet_item_to_dna");
    public static final string_id SID_CONVERT_PROMPT = new string_id("incubator", "convert_pet_item_prompt");
    public static final string_id SID_CONVERT_TITLE = new string_id("incubator", "convert_pet_item_title");
    public static final string_id SID_REPORT_PET_CONVERSION_FAIL = new string_id("incubator", "report_pet_conversion_fail");
    public int OnInitialize(obj_id self) throws InterruptedException
    {
        float scaleAdjust = 1;
        if (hasObjVar(self, "creature_attribs.scale"))
        {
            scaleAdjust = getFloatObjVar(self, "creature_attribs.scale");
        }
        float baseScale = getScale(self);
        float realScale = baseScale * scaleAdjust;
        setScale(self, realScale);
        return SCRIPT_CONTINUE;
    }
    public int OnObjectMenuRequest(obj_id self, obj_id player, menu_info mi) throws InterruptedException
    {
        if (utils.isNestedWithinAPlayer(self))
        {
            if (PRECU_DEED_UNPACK_ENABLED && canManipulate(player, self, true, true, 15, true))
            {
                mi.addRootMenu(menu_info_types.ITEM_USE, SID_UNPACK);
            }
            if (beast_lib.canUseIncubator(player))
            {
                int management_root = mi.addRootMenu(menu_info_types.SERVER_MENU1, SID_CONVERT_PET_ITEM_TO_DNA);
            }
        }
        return SCRIPT_CONTINUE;
    }
    public int OnObjectMenuSelect(obj_id self, obj_id player, int item) throws InterruptedException
    {
        if (PRECU_DEED_UNPACK_ENABLED && item == menu_info_types.ITEM_USE && utils.isNestedWithinAPlayer(self))
        {
            unpackDeed(self, player);
            return SCRIPT_CONTINUE;
        }
        if (item == menu_info_types.SERVER_MENU1 && utils.isNestedWithinAPlayer(self))
        {
            if (beast_lib.canUseIncubator(player))
            {
                int pid = sui.msgbox(self, player, "@" + SID_CONVERT_PROMPT, sui.YES_NO, "@" + SID_CONVERT_TITLE, "handlerSuiConvertPetItemToDna");
                return SCRIPT_CONTINUE;
            }
        }
        return SCRIPT_CONTINUE;
    }
    public int handlerSuiConvertPetItemToDna(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null || params.isEmpty())
        {
            return SCRIPT_CONTINUE;
        }
        int bp = sui.getIntButtonPressed(params);
        if (bp == sui.BP_CANCEL)
        {
            return SCRIPT_CONTINUE;
        }
        obj_id player = sui.getPlayerId(params);
        obj_id petItem = self;
        if (beast_lib.canUseIncubator(player))
        {
            if (incubator.convertDeedIntoPetItem(player, self))
            {
                if (incubator.convertPetItemToDna(player, petItem))
                {
                    CustomerServiceLog("BeastPetConversion: ", "petItem (" + petItem + ")" + " was converted into a dna object, for player " + getFirstName(player) + "(" + player + "). PetItem(" + petItem + ") will now be destroyed.");
                    destroyObject(petItem);
                    return SCRIPT_CONTINUE;
                }
                else 
                {
                    CustomerServiceLog("BeastPetConversion: ", "petItem (" + petItem + ")" + " was NOT converted into a dna object, for player " + getFirstName(player) + "(" + player + "). PetItem(" + petItem + ") will NOT be destroyed.");
                    sendSystemMessage(player, SID_REPORT_PET_CONVERSION_FAIL);
                }
            }
        }
        return SCRIPT_CONTINUE;
    }
    public void unpackDeed(obj_id deed, obj_id player) throws InterruptedException
    {
        if (!canManipulate(player, deed, true, true, 15, true))
        {
            return;
        }
        String creatureName = getStringObjVar(deed, "creature_attribs.type");
        if (creatureName == null || creatureName.equals(""))
        {
            sendSystemMessage(player, "This pet deed has no creature type and cannot be unpacked.", "");
            return;
        }
        String templateName = utils.dataTableGetString(create.CREATURE_TABLE, creatureName, "template");
        if (templateName == null || templateName.equals(""))
        {
            LOG("create", "PET DEED UNPACK: no template in creatures.tab for " + creatureName);
            sendSystemMessage(player, "This pet deed cannot be unpacked (unknown creature " + creatureName + ").", "");
            return;
        }
        int petType = pet_lib.getPetType(creatureName);
        if (pet_lib.hasMaxStoredPetsOfType(player, petType))
        {
            sendSystemMessage(player, beast_lib.SID_MAXIMUM_COMBAT_CONTROL_DEVICES);
            return;
        }
        obj_id datapad = utils.getPlayerDatapad(player);
        if (!isIdValid(datapad))
        {
            return;
        }
        obj_id pcd = createObject("object/intangible/pet/" + templateName, datapad, "");
        if (!isIdValid(pcd))
        {
            sendSystemMessage(player, pet_lib.SID_SYS_TOO_MANY_STORED_PETS);
            return;
        }
        setName(pcd, new string_id(create.CREATURE_NAME_FILE, creatureName));
        setObjVar(pcd, "pet.creatureName", creatureName);
        attachScript(pcd, "ai.pet_control_device");
        setObjVar(pcd, "ai.pet.type", petType);
        setObjVar(pcd, "ai.petAdvance.growthStage", UNPACK_GROWTH_STAGE);
        if (hasObjVar(deed, "creature_attribs.level") && hasObjVar(deed, "creature_attribs.maxHealth"))
        {
            // Bio-engineered deed: carry the stats over (same path the droid deed uses).
            setObjVar(pcd, "pet.crafted", true);
            pet_lib.initPCDCraftedStats(pcd, deed);
            if (hasObjVar(deed, "creature_attribs.hue"))
            {
                setObjVar(pcd, "creature_attribs.hue", getIntObjVar(deed, "creature_attribs.hue"));
            }
            ranged_int_custom_var[] ri = hue.getPalcolorVars(deed);
            if (ri != null && ri.length > 0)
            {
                for (ranged_int_custom_var var : ri)
                {
                    int val = var.getValue();
                    if (val > -1)
                    {
                        setObjVar(pcd, pet_lib.VAR_PALVAR_VARS + "." + var.getVarName(), val);
                    }
                }
                setObjVar(pcd, pet_lib.VAR_PALVAR_CNT, pet_lib.CUSTOMIZATION_COUNT);
            }
        }
        CustomerServiceLog("pet_deed", "[PRECU-DEED-1] pet deed unpacked: deed=" + deed + " creature=" + creatureName + " pcd=" + pcd + " player=" + player + "(" + getPlayerName(player) + ")");
        sendSystemMessage(player, "Your pet deed has been turned into a control device in your datapad. Call it from there.", "");
        destroyObject(deed);
    }
}
