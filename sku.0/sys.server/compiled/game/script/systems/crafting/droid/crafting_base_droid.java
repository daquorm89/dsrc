package script.systems.crafting.droid;

import script.dictionary;
import script.draft_schematic;
import script.library.craftinglib;
import script.library.pet_lib;
import script.library.utils;
import script.obj_id;
import script.modifiable_int;

public class crafting_base_droid extends script.systems.crafting.crafting_base
{
    public crafting_base_droid()
    {
    }
    public static final String VERSION = "v1.00.00";
    public static final int COMBAT_MODULE_PROTECT_LVL = 500;
    public static final int DEFENSE_MODULE_PROTECT_LVL = 1000;
    public static final int DEFAULT_DROID_LEVEL = 1;
    public static final String TBL_MOB_STAT_BALANCE = "datatables/mob/stat_balance.iff";
    public static final String TBL_DROID_COMBAT = "datatables/combat/droid_combat_capabilities.iff";
    public static final String DROID_RANGED_WEAPON = "object/weapon/ranged/vehicle/droid_weapon.iff";
    public void calcAndSetPrototypeProperties(obj_id prototype, draft_schematic.attribute[] itemAttributes) throws InterruptedException
    {
        obj_id deed = prototype;
        int tempCreatureLevel = 0;
        int tempCombatModule = 0;
        int tempArmorModule = 0;
        int tempCreatureDamageLevel = 0;
        int tempCreatureDefenseLevel = 0;
        int tempCreatureProtectionLevel = 0;
        dictionary droidCombatStats = new dictionary();
        int powerLevel = 0;
        debugServerConsoleMsg(null, "Beginning assembly-phase prototype property setting");
        for (draft_schematic.attribute itemAttribute : itemAttributes) {
            if (itemAttribute == null) {
                continue;
            }
            if (!calcAndSetPrototypeProperty(prototype, itemAttribute)) {
                switch (((itemAttribute.name).getAsciiId())) {
                    case "mechanism_quality":
                        setObjVar(prototype, "mechanism_quality", (int) (itemAttribute.currentValue));
                        break;
                    case "storage_module":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "storageModuleRating", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "data_module":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "dataModuleRating", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "personality_module":
                        int tempPersonality = (int) (itemAttribute.currentValue);
                        if (tempPersonality > 0) {
                            if (tempPersonality <= 5) {
                                setObjVar(prototype, "ai.diction", "droid_stupid");
                            } else if (tempPersonality <= 10) {
                                setObjVar(prototype, "ai.diction", "droid_sarcastic");
                            } else if (tempPersonality <= 15) {
                                setObjVar(prototype, "ai.diction", "droid_prissy");
                            } else if (tempPersonality <= 20) {
                                setObjVar(prototype, "ai.diction", "droid_worshipful");
                            } else if (tempPersonality <= 25) {
                                setObjVar(prototype, "ai.diction", "droid_slang");
                            } else if (tempPersonality <= 30) {
                                setObjVar(prototype, "ai.diction", "droid_geek");
                            }
                        } else {
                            setObjVar(prototype, "ai.diction", "droid_default");
                        }
                        break;
                    case "medical_module":
                        int medPower = (int) (itemAttribute.currentValue);
                        if (medPower > 0) {
                            if (medPower <= 2) {
                                setObjVar(prototype, "medpower", 0.55f);
                            } else if (medPower <= 4) {
                                setObjVar(prototype, "medpower", 0.65f);
                            } else if (medPower <= 6) {
                                setObjVar(prototype, "medpower", 0.75f);
                            } else if (medPower <= 8) {
                                setObjVar(prototype, "medpower", 0.85f);
                            } else if (medPower <= 10) {
                                setObjVar(prototype, "medpower", 1.00f);
                            } else if (medPower > 10) {
                                setObjVar(prototype, "medpower", 1.10f);
                            }
                        }
                        break;
                    case "repair_module":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "repair_module", itemAttribute.currentValue);
                            setObjVar(prototype, "ai.pet.isRepairDroid", true);
                        }
                        break;
                    case "armor_module":
                        tempArmorModule = (int) itemAttribute.currentValue;
                        break;
                    case "armorEffectiveness":
                        break;
                    case "armor_toughness":
                        break;
                    case "power_level":
                        if (itemAttribute.currentValue > 0) {
                            powerLevel = (int) (itemAttribute.currentValue);
                        }
                        break;
                    case "crafting_module":
                        if ((itemAttribute.currentValue > 0) && (itemAttribute.currentValue < 100000)) {
                            setObjVar(prototype, "craftingStation", true);
                            int craftingModuleValue = (int) (itemAttribute.currentValue);
                            if (craftingModuleValue >= 10000) {
                                setObjVar(prototype, "craftingStationSpace", true);
                                craftingModuleValue = (craftingModuleValue - ((craftingModuleValue / 10000) * 10000));
                            }
                            if (craftingModuleValue >= 1000) {
                                setObjVar(prototype, "craftingStationStructure", true);
                                craftingModuleValue = (craftingModuleValue - ((craftingModuleValue / 1000) * 1000));
                            }
                            if (craftingModuleValue >= 100) {
                                setObjVar(prototype, "craftingStationClothing", true);
                                craftingModuleValue = (craftingModuleValue - ((craftingModuleValue / 100) * 100));
                            }
                            if (craftingModuleValue >= 10) {
                                setObjVar(prototype, "craftingStationFood", true);
                                craftingModuleValue = (craftingModuleValue - ((craftingModuleValue / 10) * 10));
                            }
                            if (craftingModuleValue >= 1) {
                                setObjVar(prototype, "craftingStationWeapon", true);
                            }
                        }
                        break;
                                        case "droid_command_module":
                        // Pre-CU multi-droid command. itemAttribute.currentValue is the schematic's
                        // CURRENT MERGED TOTAL for this attribute (already summed across any stacked
                        // droid_command_module ingredients) - it is a snapshot of full state, not a delta.
                        // calcAndSetPrototypeProperties() fires multiple times per single craft
                        // (OnCraftingExperiment per experiment click, OnManufacturingSchematicCreation
                        // x2, OnFinalizeSchematic) so this MUST overwrite, not add to existing, or the
                        // value multiplies with every re-fire (e.g. a rating of 3 becoming 12 after 4
                        // calls). Every other case in this switch (storage_module, personality_module,
                        // etc.) already treats currentValue this way - this case was the one exception.
                        // Runtime: only the highest-total out droid grants extras; 1-5 direct or 6-100 quality map.
                        {
                            int cmd = (int)(itemAttribute.currentValue + 0.5f);
                            if (cmd > 0)
                            {
                                setObjVar(prototype, "module_data.droid_command", cmd);
                            }
                        }
                        break;
case "merchant_barker":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.merchant_barker", true);
                        }
                        break;
                    case "bomb_level":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.bomb_level", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "stimpack_capacity":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.stimpack_capacity", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "stimpack_speed":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.stimpack_speed", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "auto_repair_power":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.auto_repair_power", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "playback_module":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.playback.modules", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "harvest_power":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.harvest_power", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "entertainer_effects":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.entertainer_effects", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "struct_module":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.struct_maint", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "trap_bonus":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.trap_bonus", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "cmbt_module":
                        tempCombatModule = (int) itemAttribute.currentValue;
                        break;
                    case "fire_potency":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.fire_potency", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "arc_projector":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.arc_projector", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "shield_heatsink":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.shield_heatsink", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "pain_inducer":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.pain_inducer", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "quickset_metal":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.quickset_metal", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "dump_capacitors":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.dump_capacitors", (int) (itemAttribute.currentValue));
                        }
                        break;
                    case "sampling_power":
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, "module_data.sampling_power", (int) (itemAttribute.currentValue));
                        }
                        break;
                    default:
                        if (itemAttribute.currentValue > 0) {
                            setObjVar(prototype, craftinglib.COMPONENT_ATTRIBUTE_OBJVAR_NAME + "." + (itemAttribute.name).getAsciiId(), itemAttribute.currentValue);
                        }
                        break;
                }
            }
        }
        String droidName = getCreatureName();
        setObjVar(prototype, "creature_attribs.type", droidName);
        if (tempArmorModule > 0 || tempCombatModule > 0)
        {
            droidCombatStats.put("defaultDroidLevel", DEFAULT_DROID_LEVEL);
            droidCombatStats.put("armorModuleValue", tempArmorModule);
            droidCombatStats.put("combatModuleValue", tempCombatModule);
            pet_lib.initDroidCombatStats(deed, droidCombatStats);
        }
        else 
        {
            pet_lib.initDroidDefaultStats(deed);
        }
    }
    /**
     * Before assembly finishes, read droid_command_module from slotted ingredient
     * components and ensure module_data.droid_command is on the deed prototype.
     * Does not rely on schematic attribute merge (which can miss if IFF is stale).
     */
    public int OnManufacturingSchematicCreation(obj_id self, obj_id player, obj_id prototype, draft_schematic schematic, modifiable_int assemblyResult, modifiable_int experimentPoints) throws InterruptedException
    {
        int cmdTotal = 0;
        if (schematic != null)
        {
            draft_schematic.slot[] slots = schematic.getSlots();
            if (slots != null)
            {
                for (int i = 0; i < slots.length; i++)
                {
                    if (slots[i] == null || slots[i].ingredients == null)
                    {
                        continue;
                    }
                    for (int j = 0; j < slots[i].ingredients.length; j++)
                    {
                        if (slots[i].ingredients[j] == null)
                        {
                            continue;
                        }
                        obj_id ing = slots[i].ingredients[j].ingredient;
                        if (!isIdValid(ing) || !exists(ing))
                        {
                            continue;
                        }
                        String key = craftinglib.COMPONENT_ATTRIBUTE_OBJVAR_NAME + ".droid_command_module";
                        if (hasObjVar(ing, key))
                        {
                            float f = getFloatObjVar(ing, key);
                            int v = (int)(f + 0.5f);
                            if (v <= 0)
                            {
                                v = getIntObjVar(ing, key);
                            }
                            if (v > 0)
                            {
                                cmdTotal += v;
                            }
                        }
                    }
                }
            }
        }
        if (cmdTotal > 0)
        {
            utils.setScriptVar(self, "precu.pending_droid_command", cmdTotal);
        }
        int result = super.OnManufacturingSchematicCreation(self, player, prototype, schematic, assemblyResult, experimentPoints);
        if (utils.hasScriptVar(self, "precu.pending_droid_command") && isIdValid(prototype) && exists(prototype))
        {
            int cmd = utils.getIntScriptVar(self, "precu.pending_droid_command");
            utils.removeScriptVar(self, "precu.pending_droid_command");
            if (cmd > 0)
            {
                int existing = 0;
                if (hasObjVar(prototype, "module_data.droid_command"))
                {
                    existing = getIntObjVar(prototype, "module_data.droid_command");
                }
                if (cmd > existing)
                {
                    setObjVar(prototype, "module_data.droid_command", cmd);
                }
            }
        }
        return result;
    }
    public String getCreatureName() throws InterruptedException
    {
        return null;
    }
}
