package script.systems.crafting.furniture;

import script.dictionary;
import script.draft_schematic;
import script.library.craftinglib;
import script.obj_id;

public class crafting_base_furniture extends script.systems.crafting.crafting_base
{
    public crafting_base_furniture()
    {
    }
    public static final String VERSION = "v1.00.00";
    // P25: crafted container capacity from the quality attribute (resources OQ/DR + experimentation).
    // capacity = base + (CONTAINER_CAPACITY_MAX - base) * quality%, quality% = (current - min) / (max - min).
    // base = the template capacity (50 for the crafted chests, cabinets, armoires, bookcases, toolchests),
    // remembered in objvar container.capacity_base because the prototype is recalculated after every
    // experiment. MAX stays below the player inventory (80): the engine only lets a container into a
    // container with a larger capacity, so 80+ could no longer be picked up.
    public static final int CONTAINER_CAPACITY_MAX = 79;
    public static final String OBJVAR_CONTAINER_CAPACITY_BASE = "container.capacity_base";
    public void applyCraftedContainerCapacity(obj_id prototype, draft_schematic.attribute quality) throws InterruptedException
    {
        if (!isIdValid(prototype) || quality == null)
        {
            return;
        }
        int base;
        if (hasObjVar(prototype, OBJVAR_CONTAINER_CAPACITY_BASE))
        {
            base = getIntObjVar(prototype, OBJVAR_CONTAINER_CAPACITY_BASE);
        }
        else
        {
            base = getTotalVolume(prototype);
            if (base <= 0 || base >= CONTAINER_CAPACITY_MAX)
            {
                return;
            }
            setObjVar(prototype, OBJVAR_CONTAINER_CAPACITY_BASE, base);
        }
        float range = quality.maxValue - quality.minValue;
        float pct = range > 0 ? (quality.currentValue - quality.minValue) / range : 1.0f;
        pct = Math.max(0.0f, Math.min(1.0f, pct));
        int capacity = base + Math.round((CONTAINER_CAPACITY_MAX - base) * pct);
        if (!setContainerCapacity(prototype, capacity))
        {
            LOG("crafting", "[containercap P25-1] setContainerCapacity failed for " + prototype + " (" + getTemplateName(prototype) + "), server binary without P25?");
        }
    }
    public void calcAndSetPrototypeProperties(obj_id prototype, draft_schematic.attribute[] itemAttributes, dictionary craftingValuesDictionary) throws InterruptedException
    {
        super.calcAndSetPrototypeProperties(prototype, itemAttributes, craftingValuesDictionary);
    }
    public void calcAndSetPrototypeProperties(obj_id prototype, draft_schematic.attribute[] itemAttributes) throws InterruptedException
    {
        debugServerConsoleMsg(null, "Beginning assembly-phase prototype property setting");
        for (draft_schematic.attribute itemAttribute : itemAttributes) {
            if (itemAttribute == null) {
                continue;
            }
            if (!calcAndSetPrototypeProperty(prototype, itemAttribute)) {
                if (((itemAttribute.name).getAsciiId()).equals("quality")) {
                    setObjVar(prototype, "item_quality", itemAttribute.currentValue);
                    applyCraftedContainerCapacity(prototype, itemAttribute);
                } else if (((itemAttribute.name).getAsciiId()).equals("lifespan")) {
                    setObjVar(prototype, "decayTime", ((int) (itemAttribute.currentValue) * 24 * 60 * 60));
                } else {
                    setObjVar(prototype, craftinglib.COMPONENT_ATTRIBUTE_OBJVAR_NAME + "." + (itemAttribute.name).getAsciiId(), itemAttribute.currentValue);
                }
            }
        }
    }
}
