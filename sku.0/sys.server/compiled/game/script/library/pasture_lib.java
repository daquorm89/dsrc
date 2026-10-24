package script.library;

import script.*;

/**
 * Pasture: the creature-pet counterpart of the stationed ("set loose") droids (P18).
 *
 * A called-out creature pet can be put out to pasture by its owner. It stops being a follower
 * pet, frees its datapad call slot, is persisted in the world where it stands and roams around
 * that spot (or stays put). "Retrieve" returns it to the datapad exactly as it was.
 *
 * Same key design as script.library.stationed_droid: everything a tamed creature is (name,
 * growth stage, trained commands, abilities, mount training, colours, stored damage) lives on its
 * pet control device (PCD). The PCD is never destroyed; while the animal is out to pasture the PCD
 * is PARKED inside the animal's own inventory container with ai.pet_control_device detached
 * (that script destroys the linked creature whenever the PCD moves).
 *
 * Pastured animals are invulnerable (PASTURE_INVULNERABLE): they never fight, never die and never
 * get aggro trigger volumes, so no PCD can be lost to a kill and no pasture attacks passers-by.
 *
 * Animal-side objvars (persisted):
 *   pasture.owner        obj_id   owning player (also kept as the engine master)
 *   pasture.mode         int      MODE_*
 *   pasture.pcd          obj_id   the parked PCD
 *   pasture.pcdScripts   String[] scripts detached from the PCD (re-attached on retrieve)
 *   pasture.home         location the spot it roams around
 * PCD-side objvars (travel with the pet into and out of the datapad):
 *   pasture.gender       int      GENDER_MALE / GENDER_FEMALE, assigned the first time it is pastured
 * Player-side: pasture.animals obj_id[] (registry used for the per-player cap).
 */
public class pasture_lib extends script.base_script
{
    public pasture_lib()
    {
    }
    public static final String SCRIPT = "ai.pasture";
    public static final String STRING_FILE = "pet/pet_menu";

    public static final String OBJVAR_ROOT = "pasture";
    public static final String OBJVAR_OWNER = "pasture.owner";
    public static final String OBJVAR_MODE = "pasture.mode";
    public static final String OBJVAR_PCD = "pasture.pcd";
    public static final String OBJVAR_PCD_SCRIPTS = "pasture.pcdScripts";
    public static final String OBJVAR_HOME = "pasture.home";
    public static final String OBJVAR_PLAYER_LIST = "pasture.animals";
    public static final String OBJVAR_GENDER = "pasture.gender";

    public static final int MODE_ROAM = 0;
    public static final int MODE_STAY = 1;

    public static final int GENDER_MALE = 0;
    public static final int GENDER_FEMALE = 1;

    public static final String[] PCD_SCRIPTS = { "ai.pet_control_device", "ai.pcd_ping_response" };
    public static final String[] PET_SCRIPTS = { "ai.pet_ping", "ai.pet" };

    public static final int MAX_PASTURED_PER_PLAYER = 25;
    /** Pastured animals cannot be hurt (no combat, no death, no aggro volumes). */
    public static final boolean PASTURE_INVULNERABLE = true;

    // ---- roaming (driven by ai.pasture, not by the engine loiter) -------------------------
    public static final float ROAM_RANGE = 15.0f;          // max distance from the pasture spot
    public static final float ROAM_RANGE_INDOOR = 6.0f;
    public static final float ROAM_STEP_MIN = 2.0f;
    public static final float ROAM_STEP_MAX = 8.0f;
    public static final float ROAM_STEP_MAX_INDOOR = 4.0f;
    public static final float ROAM_MAX_RISE = 1.5f;
    public static final int ROAM_PICK_TRIES = 8;
    public static final int ROAM_FORWARD_TRIES = 5;
    public static final float ROAM_FORWARD_CONE_DEG = 90.0f;
    // Grazing animals pause longer than droids between steps.
    public static final float ROAM_PAUSE_MIN_SEC = 5.0f;
    public static final float ROAM_PAUSE_MAX_SEC = 15.0f;
    public static final float ROAM_LONG_PAUSE_CHANCE = 0.2f;
    public static final float ROAM_LONG_PAUSE_MIN_SEC = 20.0f;
    public static final float ROAM_LONG_PAUSE_MAX_SEC = 45.0f;
    public static final float ROAM_RETRY_MIN_SEC = 3.0f;
    public static final float ROAM_RETRY_MAX_SEC = 6.0f;

    /** Shown in debug messages so a chat log proves which build runs. Bump on every change. */
    public static final String BUILD_TAG = "PASTURE-1";
    public static final boolean DEBUG_MESSAGES = false;

    public static string_id sid(String key)
    {
        return new string_id(STRING_FILE, key);
    }

    /** Plain-text system message (no string table entry needed). */
    public static void say(obj_id player, String text) throws InterruptedException
    {
        if (isIdValid(player) && exists(player))
        {
            sendSystemMessage(player, text, null);
        }
    }

    public static void dbg(obj_id owner, String msg) throws InterruptedException
    {
        LOG("pasture", msg);
        if (DEBUG_MESSAGES && isIdValid(owner) && exists(owner))
        {
            sendSystemMessage(owner, "[pasture " + BUILD_TAG + "] " + msg, null);
        }
    }

    // ---- state ------------------------------------------------------------------------

    public static boolean isPastured(obj_id animal) throws InterruptedException
    {
        return isIdValid(animal) && exists(animal) && hasObjVar(animal, OBJVAR_OWNER);
    }

    public static obj_id getPastureOwner(obj_id animal) throws InterruptedException
    {
        if (!isIdValid(animal) || !hasObjVar(animal, OBJVAR_OWNER))
        {
            return null;
        }
        return getObjIdObjVar(animal, OBJVAR_OWNER);
    }

    public static int getMode(obj_id animal) throws InterruptedException
    {
        if (!isIdValid(animal) || !hasObjVar(animal, OBJVAR_MODE))
        {
            return MODE_ROAM;
        }
        return getIntObjVar(animal, OBJVAR_MODE);
    }

    public static obj_id getParkedPcd(obj_id animal) throws InterruptedException
    {
        if (!isIdValid(animal) || !hasObjVar(animal, OBJVAR_PCD))
        {
            return null;
        }
        return getObjIdObjVar(animal, OBJVAR_PCD);
    }

    public static location getHome(obj_id animal) throws InterruptedException
    {
        return hasObjVar(animal, OBJVAR_HOME) ? getLocationObjVar(animal, OBJVAR_HOME) : getLocation(animal);
    }

    /** Gender lives on the PCD so it survives retrieve / pasture. Assigned at random on first use. */
    public static int getPastureGender(obj_id pcd) throws InterruptedException
    {
        if (!isIdValid(pcd) || !exists(pcd))
        {
            return GENDER_MALE;
        }
        if (!hasObjVar(pcd, OBJVAR_GENDER))
        {
            setObjVar(pcd, OBJVAR_GENDER, rand(0, 1) == 0 ? GENDER_MALE : GENDER_FEMALE);
        }
        return getIntObjVar(pcd, OBJVAR_GENDER);
    }

    public static String genderName(int gender)
    {
        return gender == GENDER_FEMALE ? "female" : "male";
    }

    /** Display name of the animal for plain-text messages. */
    public static String displayName(obj_id animal) throws InterruptedException
    {
        String name = getAssignedName(animal);
        if (name == null || name.length() == 0 || name.equals("null"))
        {
            string_id nsid = getNameStringId(animal);
            name = nsid != null ? getString(nsid) : null;
        }
        if (name == null || name.length() == 0)
        {
            name = "animal";
        }
        return name;
    }

    // ---- per-player registry (cap) --------------------------------------------------------
    // Entries are not validated with exists(): an animal on another planet server is not loaded
    // here but still belongs to the player. They leave on retrieve or when the animal is destroyed.

    public static obj_id[] getPasturedAnimals(obj_id player) throws InterruptedException
    {
        if (!isIdValid(player) || !hasObjVar(player, OBJVAR_PLAYER_LIST))
        {
            return new obj_id[0];
        }
        obj_id[] list = getObjIdArrayObjVar(player, OBJVAR_PLAYER_LIST);
        return list == null ? new obj_id[0] : list;
    }

    public static int countPastured(obj_id player) throws InterruptedException
    {
        return getPasturedAnimals(player).length;
    }

    public static void registerAnimal(obj_id player, obj_id animal) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(animal))
        {
            return;
        }
        obj_id[] list = getPasturedAnimals(player);
        for (obj_id id : list)
        {
            if (id == animal)
            {
                return;
            }
        }
        obj_id[] next = new obj_id[list.length + 1];
        System.arraycopy(list, 0, next, 0, list.length);
        next[list.length] = animal;
        setObjVar(player, OBJVAR_PLAYER_LIST, next);
    }

    public static void unregisterAnimal(obj_id player, obj_id animal) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(animal))
        {
            return;
        }
        obj_id[] list = getPasturedAnimals(player);
        java.util.Vector keep = new java.util.Vector();
        for (obj_id id : list)
        {
            if (id != animal)
            {
                keep.add(id);
            }
        }
        if (keep.size() == list.length)
        {
            return;
        }
        if (keep.isEmpty())
        {
            removeObjVar(player, OBJVAR_PLAYER_LIST);
            return;
        }
        setObjVar(player, OBJVAR_PLAYER_LIST, (obj_id[])keep.toArray(new obj_id[0]));
    }

    // ---- gate ---------------------------------------------------------------------------

    /** True when the radial entry "Pasture" should be offered on this called-out pet. */
    public static boolean canOfferPasture(obj_id pet, obj_id player) throws InterruptedException
    {
        if (!isIdValid(pet) || !isIdValid(player) || getMaster(pet) != player)
        {
            return false;
        }
        if (pet_lib.isDroidPet(pet) || !ai_lib.isMonster(pet) || isPastured(pet))
        {
            return false;
        }
        int type = pet_lib.getPetType(pet);
        return type == pet_lib.PET_TYPE_AGGRO || type == pet_lib.PET_TYPE_NON_AGGRO || type == pet_lib.PET_TYPE_MOUNT;
    }

    /** Returns null when the pet may be put out to pasture, otherwise the reason (plain text). */
    public static String getPastureBlocker(obj_id pet, obj_id player) throws InterruptedException
    {
        if (!isIdValid(pet) || !exists(pet) || !isIdValid(player) || !exists(player))
        {
            return "That animal cannot be put out to pasture.";
        }
        if (!canOfferPasture(pet, player))
        {
            return "Only your own called-out creature pets can be put out to pasture.";
        }
        obj_id pcd = callable.getCallableCD(pet);
        if (!isIdValid(pcd) || !exists(pcd))
        {
            return "That animal cannot be put out to pasture.";
        }
        if (pet_lib.isMountedOnCreatureQueried(pet, player) || pet_lib.isMounted(player))
        {
            return "You must dismount first.";
        }
        if (ai_lib.isInCombat(player) || ai_lib.isInCombat(pet) || ai_lib.isAiDead(pet) || isIncapacitated(pet))
        {
            return "You cannot do that during combat.";
        }
        if (isSpaceScene() || isIdValid(space_transition.getContainingShip(player)) || isIdValid(space_transition.getContainingShip(pet)))
        {
            return "Animals cannot be put out to pasture here.";
        }
        obj_id inv = utils.getInventoryContainer(pet);
        if (!isIdValid(inv) || !exists(inv))
        {
            return "That animal cannot be put out to pasture.";
        }
        if (countPastured(player) >= MAX_PASTURED_PER_PLAYER)
        {
            return "You already have the maximum of " + MAX_PASTURED_PER_PLAYER + " animals out to pasture.";
        }
        return null;
    }

    // ---- script helpers ----------------------------------------------------------------

    private static String[] detachPresent(obj_id obj, String[] scripts) throws InterruptedException
    {
        java.util.Vector gone = new java.util.Vector();
        for (String s : scripts)
        {
            if (hasScript(obj, s))
            {
                detachScript(obj, s);
                gone.add(s);
            }
        }
        return (String[])gone.toArray(new String[0]);
    }

    private static void attachMissing(obj_id obj, String[] scripts) throws InterruptedException
    {
        for (String s : scripts)
        {
            if (!hasScript(obj, s))
            {
                attachScript(obj, s);
            }
        }
    }

    // ---- pasture -----------------------------------------------------------------------

    /** Radial "Pasture" on a called-out creature pet. */
    public static boolean setPasture(obj_id pet, obj_id player) throws InterruptedException
    {
        String why = getPastureBlocker(pet, player);
        if (why != null)
        {
            say(player, why);
            return false;
        }
        obj_id pcd = callable.getCallableCD(pet);
        // Flush damage / name / colours into the PCD while it is still linked and loaded.
        pet_lib.savePetInfo(pet, pcd);
        pet_lib.removeFromPetList(pet);
        if (!parkIntoPasture(pet, player, pcd, getLocation(pet)))
        {
            say(player, "Your pet could not be put out to pasture; it was returned to your datapad.");
            pet_lib.storePet(pet, player);
            return false;
        }
        // The animal stops being a follower pet. ai.pet.OnDetach clears the "ai.pet" objvars.
        detachPresent(pet, PET_SCRIPTS);
        finishPasture(pet);
        say(player, "Your " + displayName(pet) + " (" + genderName(getPastureGender(pcd)) + ") is now out to pasture.");
        return true;
    }

    /**
     * Persists the animal, parks its PCD in the animal's inventory and writes the pasture objvars.
     * Every step that can fail runs before anything irreversible; on failure the PCD stays usable
     * in the datapad and false is returned (the caller decides what happens to the animal).
     */
    public static boolean parkIntoPasture(obj_id animal, obj_id owner, obj_id pcd, location home) throws InterruptedException
    {
        obj_id inv = utils.getInventoryContainer(animal);
        if (!isIdValid(inv) || !exists(inv) || !isIdValid(pcd) || !exists(pcd))
        {
            return false;
        }
        // Persist first, otherwise the PCD would sit in a non-persisted container and be lost on restart.
        if (!persistObject(animal) && !isObjectPersisted(animal))
        {
            dbg(owner, "parkIntoPasture: could not persist " + animal);
            return false;
        }
        getPastureGender(pcd);
        setObjVar(animal, OBJVAR_OWNER, owner);
        setObjVar(animal, OBJVAR_MODE, MODE_ROAM);
        setObjVar(animal, OBJVAR_PCD, pcd);
        setObjVar(animal, OBJVAR_HOME, home);

        // ai.pet_control_device.OnTransferred destroys the linked creature when the PCD moves.
        String[] gone = detachPresent(pcd, PCD_SCRIPTS);
        setObjVar(animal, OBJVAR_PCD_SCRIPTS, gone);
        callable.setCDCallable(pcd, null);
        if (!putIn(pcd, inv))
        {
            attachMissing(pcd, gone);
            callable.setCDCallable(pcd, animal);
            removeObjVar(animal, OBJVAR_ROOT);
            dbg(owner, "parkIntoPasture: putIn(pcd " + pcd + ", inventory " + inv + ") refused");
            return false;
        }
        registerAnimal(owner, animal);
        return true;
    }

    /** Last steps once the PCD is parked: protection, behaviour script, master link. */
    public static void finishPasture(obj_id animal) throws InterruptedException
    {
        obj_id owner = getPastureOwner(animal);
        if (isIdValid(owner))
        {
            setMaster(animal, owner);
        }
        // A pastured animal must never offer "Tame" to other players.
        if (hasScript(animal, "ai.pet_advance"))
        {
            detachScript(animal, "ai.pet_advance");
        }
        utils.removeScriptVar(animal, "petBeingInitialized");
        utils.removeScriptVar(animal, "ai.pet.staying");
        if (PASTURE_INVULNERABLE)
        {
            setInvulnerable(animal, true);
        }
        clearFollowState(animal);
        if (!hasScript(animal, SCRIPT))
        {
            attachScript(animal, SCRIPT);
        }
        applyMode(animal);
    }

    // ---- retrieve ----------------------------------------------------------------------

    /** Returns a pastured animal to its owner's datapad (restoring the PCD) and removes it. */
    public static boolean retrieve(obj_id animal, obj_id player) throws InterruptedException
    {
        if (!isPastured(animal) || !isIdValid(player) || getPastureOwner(animal) != player)
        {
            say(player, "That is not your animal.");
            return false;
        }
        obj_id pcd = getParkedPcd(animal);
        obj_id datapad = utils.getPlayerDatapad(player);
        if (!isIdValid(pcd) || !exists(pcd) || !isIdValid(datapad) || !exists(datapad))
        {
            say(player, "The animal could not be retrieved.");
            return false;
        }
        int petType = hasObjVar(pcd, "ai.pet.type") ? getIntObjVar(pcd, "ai.pet.type") : pet_lib.PET_TYPE_NON_AGGRO;
        if (pet_lib.hasMaxStoredPetsOfType(player, petType))
        {
            sendSystemMessage(player, pet_lib.SID_SYS_TOO_MANY_STORED_PETS);
            return false;
        }
        if (!returnPcdToDatapad(animal, pcd, datapad))
        {
            say(player, "The animal could not be retrieved.");
            return false;
        }
        pet_lib.savePetInfo(animal, pcd);
        unregisterAnimal(player, animal);
        utils.setScriptVar(animal, "stored", true);
        String name = displayName(animal);
        if (!destroyObject(animal))
        {
            LOG("pasture", "retrieve: failed to destroy " + animal + " after returning PCD " + pcd);
        }
        say(player, "Your " + name + " was brought back from the pasture to your datapad.");
        return true;
    }

    /** Moves the parked PCD back into the datapad and makes it a normal stored PCD again. */
    private static boolean returnPcdToDatapad(obj_id animal, obj_id pcd, obj_id datapad) throws InterruptedException
    {
        if (!putIn(pcd, datapad))
        {
            return false;
        }
        String[] scripts = hasObjVar(animal, OBJVAR_PCD_SCRIPTS) ? getStringArrayObjVar(animal, OBJVAR_PCD_SCRIPTS) : new String[]{ "ai.pet_control_device" };
        if (scripts == null || scripts.length == 0)
        {
            scripts = new String[]{ "ai.pet_control_device" };
        }
        callable.setCDCallable(pcd, null);
        callable.setCallableCD(animal, pcd);
        setObjVar(pcd, "pet.timeStored", getGameTime());
        setCount(pcd, 0);
        attachMissing(pcd, scripts);
        sendDirtyObjectMenuNotification(pcd);
        return true;
    }

    /**
     * Last-resort recovery when a pastured animal is destroyed by something else (GM /destroy,
     * lava) while its PCD is still parked inside it. Returns true when the PCD is safe.
     */
    public static boolean rescuePcd(obj_id animal) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(animal);
        obj_id owner = getPastureOwner(animal);
        if (!isIdValid(pcd) || !exists(pcd))
        {
            return false;
        }
        obj_id inv = utils.getInventoryContainer(animal);
        if (isIdValid(inv) && getContainedBy(pcd) != inv)
        {
            return true;
        }
        obj_id datapad = isIdValid(owner) ? utils.getPlayerDatapad(owner) : null;
        if (!isIdValid(datapad) || !exists(datapad) || !returnPcdToDatapad(animal, pcd, datapad))
        {
            LOG("pasture", "rescuePcd: could not return PCD " + pcd + " of " + animal + " to owner " + owner + "; it is lost with the animal");
            return false;
        }
        unregisterAnimal(owner, animal);
        say(owner, "Your pastured " + displayName(animal) + " was returned to your datapad.");
        return true;
    }

    // ---- orders ------------------------------------------------------------------------

    /** Owner command: roam or stay. The pasture spot becomes wherever the animal stands now. */
    public static boolean setMode(obj_id animal, obj_id player, int mode) throws InterruptedException
    {
        if (!isPastured(animal) || getPastureOwner(animal) != player || (mode != MODE_ROAM && mode != MODE_STAY))
        {
            return false;
        }
        setObjVar(animal, OBJVAR_MODE, mode);
        setObjVar(animal, OBJVAR_HOME, getLocation(animal));
        messageTo(animal, "handleApplyMode", null, 0, false);
        say(player, mode == MODE_STAY ? "Your " + displayName(animal) + " will stay here." : "Your " + displayName(animal) + " will roam around this spot.");
        return true;
    }

    /** The pet follow code leaves follow / formation objvars that ai.ai would resume (walking to the owner). */
    public static void clearFollowState(obj_id animal) throws InterruptedException
    {
        if (!isIdValid(animal) || !exists(animal))
        {
            return;
        }
        String[] vars = { "ai.persistantFollowing", "ai.formationType", "ai.inFormation", "pet.patrolPoints" };
        for (String v : vars)
        {
            if (hasObjVar(animal, v))
            {
                removeObjVar(animal, v);
            }
        }
    }

    /** Pushes the stored mode into the engine AI. Called by ai.pasture on the animal itself. */
    public static void applyMode(obj_id animal) throws InterruptedException
    {
        if (!isPastured(animal))
        {
            return;
        }
        clearFollowState(animal);
        location home = getHome(animal);
        setMovementPercent(animal, 1.0f);
        setMovementWalk(animal);
        aiSetHomeLocation(animal, home);
        setHomeLocation(animal, home);
        removeObjVar(animal, "ai.loiterMinRange");
        removeObjVar(animal, "ai.loiterMaxRange");
        setWantSawAttackTriggers(animal, false);
        ai_lib.setDefaultCalmBehavior(animal, ai_lib.BEHAVIOR_STOP);
        if (getMode(animal) == MODE_STAY && !ai_lib.isInCombat(animal) && getDistance(animal, home) > 1.5f)
        {
            pathTo(animal, home);
        }
    }

    /** Growth stage from the PCD (10 = adult; tamed creatures start at 1). */
    public static int getGrowthStage(obj_id pcd) throws InterruptedException
    {
        if (!isIdValid(pcd) || !exists(pcd) || !hasObjVar(pcd, "ai.petAdvance.growthStage"))
        {
            return 10;
        }
        return getIntObjVar(pcd, "ai.petAdvance.growthStage");
    }

    public static boolean isAdult(obj_id pcd) throws InterruptedException
    {
        return getGrowthStage(pcd) >= 10;
    }

    /** One-line status for the owner's "Pasture Info" radial entry. */
    public static String describe(obj_id animal) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(animal);
        int stage = getGrowthStage(pcd);
        String text = displayName(animal) + ": " + genderName(getPastureGender(pcd)) + ", " + (stage >= 10 ? "adult" : "young (growth " + stage + "/10)") + ", " + (getMode(animal) == MODE_STAY ? "staying put" : "roaming") + ".";
        return text;
    }

    /** Range the animal may roam from its pasture spot. */
    public static float getRoamRange(obj_id animal) throws InterruptedException
    {
        location home = getHome(animal);
        return (home != null && isIdValid(home.cell)) ? ROAM_RANGE_INDOOR : ROAM_RANGE;
    }
}
