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

    // ---- mating (part 2) ------------------------------------------------------------------
    // A pastured adult female with a pastured adult male of the same species within MATE_RANGE
    // collects "mating time" on every MATE_TICK_SEC tick; when MATE_TIME_SEC is reached she gives
    // birth to one baby. The baby is pastured for the FEMALE's owner when possible (owner loaded on
    // this server, below the pasture cap, datapad accepts a new control device); otherwise it is a
    // wild baby that must be tamed. Males may belong to anyone.
    public static final boolean MATING_ENABLED = true;
    public static final float MATE_RANGE = 20.0f;
    public static final float MATE_TICK_SEC = 300.0f;            // 5 min
    public static final int MATE_TIME_SEC = 3600;                 // 1 h near a male
    public static final int MATE_COOLDOWN_SEC = 86400;            // 24 h between births per female
    public static final int OWNER_WAIT_SEC = 21600;               // a due birth waits up to 6 h for the owner, then the baby is wild
    public static final String OBJVAR_MATE_PROGRESS = "pasture.mate.progress";   // animal: seconds collected
    public static final String OBJVAR_MATE_LAST = "pasture.mate.last";           // animal: game time of the last tick
    public static final String OBJVAR_MATE_DUE = "pasture.mate.due";             // animal: birth due since (waiting for the owner)
    public static final String OBJVAR_MATE_NEXT = "pasture.mate.nextAt";         // PCD: no new birth before this game time

    /** Shown in debug messages so a chat log proves which build runs. Bump on every change. */
    public static final String BUILD_TAG = "PASTURE-2";
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
        return text + describeMating(animal);
    }

    // ---- mating ---------------------------------------------------------------------------

    public static boolean isFemale(obj_id animal) throws InterruptedException
    {
        return getPastureGender(getParkedPcd(animal)) == GENDER_FEMALE;
    }

    public static String getPastureSpecies(obj_id animal) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(animal);
        if (isIdValid(pcd) && exists(pcd) && hasObjVar(pcd, "pet.creatureName"))
        {
            return getStringObjVar(pcd, "pet.creatureName");
        }
        return getCreatureName(animal);
    }

    /** True when this pastured animal can take part in mating at all (adult, same-cell rules apply later). */
    public static boolean canBreed(obj_id animal) throws InterruptedException
    {
        if (!MATING_ENABLED || !isPastured(animal))
        {
            return false;
        }
        obj_id pcd = getParkedPcd(animal);
        String species = getPastureSpecies(animal);
        return isIdValid(pcd) && exists(pcd) && isAdult(pcd) && species != null && species.length() > 0;
    }

    /** Seconds until this female may give birth again (0 = ready). */
    public static int getCooldownLeft(obj_id female) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(female);
        if (!isIdValid(pcd) || !exists(pcd) || !hasObjVar(pcd, OBJVAR_MATE_NEXT))
        {
            return 0;
        }
        int left = getIntObjVar(pcd, OBJVAR_MATE_NEXT) - getGameTime();
        return left > 0 ? left : 0;
    }

    /** A pastured adult male of the same species near the female (same cell), or null. */
    public static obj_id findMate(obj_id female) throws InterruptedException
    {
        String species = getPastureSpecies(female);
        location here = getLocation(female);
        obj_id[] near = getCreaturesInRange(female, MATE_RANGE);
        if (near == null || species == null || here == null)
        {
            return null;
        }
        for (obj_id other : near)
        {
            if (!isIdValid(other) || other == female || !isPastured(other) || !canBreed(other) || isFemale(other))
            {
                continue;
            }
            if (!species.equals(getPastureSpecies(other)))
            {
                continue;
            }
            location there = getLocation(other);
            if (there == null || (isIdValid(here.cell) != isIdValid(there.cell)) || (isIdValid(here.cell) && !here.cell.equals(there.cell)))
            {
                continue;
            }
            return other;
        }
        return null;
    }

    /**
     * One mating tick for a pastured female (called by ai.pasture every MATE_TICK_SEC).
     * Returns true when a baby was born.
     */
    public static boolean mateTick(obj_id female) throws InterruptedException
    {
        if (!canBreed(female) || !isFemale(female))
        {
            return false;
        }
        int now = getGameTime();
        obj_id owner = getPastureOwner(female);
        if (hasObjVar(female, OBJVAR_MATE_DUE))
        {
            // Birth is due but the owner was not around: try again, after OWNER_WAIT_SEC the baby is born wild.
            boolean forceWild = now - getIntObjVar(female, OBJVAR_MATE_DUE) >= OWNER_WAIT_SEC;
            return giveBirth(female, forceWild);
        }
        if (getCooldownLeft(female) > 0)
        {
            removeObjVar(female, OBJVAR_MATE_PROGRESS);
            setObjVar(female, OBJVAR_MATE_LAST, now);
            return false;
        }
        int last = hasObjVar(female, OBJVAR_MATE_LAST) ? getIntObjVar(female, OBJVAR_MATE_LAST) : now;
        setObjVar(female, OBJVAR_MATE_LAST, now);
        obj_id male = findMate(female);
        if (!isIdValid(male))
        {
            if (hasObjVar(female, OBJVAR_MATE_PROGRESS))
            {
                removeObjVar(female, OBJVAR_MATE_PROGRESS);
                dbg(owner, "mate: " + female + " has no male nearby, progress reset");
            }
            return false;
        }
        // Count the real time since the last tick, but never more than two ticks (server restarts, lag).
        int step = now - last;
        int maxStep = (int)(MATE_TICK_SEC * 2.0f);
        if (step < 0 || step > maxStep)
        {
            step = (int)MATE_TICK_SEC;
        }
        int progress = (hasObjVar(female, OBJVAR_MATE_PROGRESS) ? getIntObjVar(female, OBJVAR_MATE_PROGRESS) : 0) + step;
        setObjVar(female, OBJVAR_MATE_PROGRESS, progress);
        dbg(owner, "mate: " + female + " with male " + male + " progress " + progress + "/" + MATE_TIME_SEC);
        if (progress < MATE_TIME_SEC)
        {
            return false;
        }
        removeObjVar(female, OBJVAR_MATE_PROGRESS);
        setObjVar(female, OBJVAR_MATE_DUE, now);
        return giveBirth(female, false);
    }

    /** True when the owner is loaded on this server with a usable datapad and room in the pasture cap. */
    public static boolean canReceiveOwnedBaby(obj_id owner) throws InterruptedException
    {
        if (!isIdValid(owner) || !owner.isLoaded() || !exists(owner))
        {
            return false;
        }
        obj_id datapad = utils.getPlayerDatapad(owner);
        return isIdValid(datapad) && exists(datapad) && countPastured(owner) < MAX_PASTURED_PER_PLAYER;
    }

    /** Spot for the newborn: about 1.5 m beside the mother, same cell, on the ground outdoors. */
    public static location getBirthSpot(obj_id female) throws InterruptedException
    {
        location here = getLocation(female);
        double ang = rand() * 6.2831853d;
        float x = here.x + (float)Math.sin(ang) * 1.5f;
        float z = here.z + (float)Math.cos(ang) * 1.5f;
        float y = here.y;
        if (!isIdValid(here.cell))
        {
            float ground = getHeightAtLocation(x, z);
            if (ground == ground && Math.abs(ground - here.y) < 3.0f)
            {
                y = ground;
            }
            else
            {
                x = here.x;
                z = here.z;
            }
        }
        return new location(x, y, z, here.area, here.cell);
    }

    /**
     * Gives birth next to the female. Pastured for her owner when possible, otherwise wild (only once
     * the owner has had OWNER_WAIT_SEC to come by, or when forceWild / the owner cannot take it).
     * Returns true when a baby exists afterwards.
     */
    public static boolean giveBirth(obj_id female, boolean forceWild) throws InterruptedException
    {
        obj_id owner = getPastureOwner(female);
        String species = getPastureSpecies(female);
        String mother = displayName(female);
        boolean ownerHere = isIdValid(owner) && owner.isLoaded() && exists(owner);
        if (!forceWild && !ownerHere)
        {
            dbg(owner, "birth: " + female + " is due, owner not on this server, waiting");
            return false;   // keep OBJVAR_MATE_DUE, retried on the next tick
        }
        location spot = getBirthSpot(female);
        obj_id baby = null;
        boolean owned = false;
        if (!forceWild && canReceiveOwnedBaby(owner))
        {
            baby = createOwnedBaby(species, spot, owner);
            owned = isIdValid(baby);
        }
        if (!owned)
        {
            baby = createWildBaby(species, spot);
        }
        removeObjVar(female, OBJVAR_MATE_DUE);
        obj_id pcd = getParkedPcd(female);
        if (isIdValid(pcd) && exists(pcd))
        {
            setObjVar(pcd, OBJVAR_MATE_NEXT, getGameTime() + MATE_COOLDOWN_SEC);
        }
        if (!isIdValid(baby))
        {
            dbg(owner, "birth: " + female + " could not create a baby of " + species);
            return false;
        }
        if (ownerHere)
        {
            if (owned)
            {
                say(owner, "Your " + mother + " has given birth! The " + genderName(getPastureGender(getParkedPcd(baby))) + " baby is out to pasture next to her.");
            }
            else
            {
                say(owner, "Your " + mother + " has given birth, but you cannot keep more animals right now: the baby is wild and must be tamed.");
            }
        }
        LOG("pasture", "birth: mother " + female + " (" + species + ") owner " + owner + " baby " + baby + (owned ? " pastured" : " wild"));
        return true;
    }

    /**
     * Creates a baby and puts it straight out to pasture for the owner: a new control device in the
     * owner's datapad (growth stage 1, like a freshly tamed baby), parked in the baby at once.
     * Returns null when that is not possible; nothing is left behind in that case.
     */
    public static obj_id createOwnedBaby(String species, location spot, obj_id owner) throws InterruptedException
    {
        obj_id baby = create.object(species, spot, true, true);
        if (!isIdValid(baby))
        {
            return null;
        }
        obj_id pcd = pet_lib.makeControlDevice(owner, baby);
        if (!isIdValid(pcd) || !exists(pcd))
        {
            destroyObject(baby);
            return null;
        }
        // makeControlDevice starts creature pets at growth stage 1; make sure a baby is a baby.
        if (!hasObjVar(pcd, "ai.petAdvance.growthStage"))
        {
            setObjVar(pcd, "ai.petAdvance.growthStage", 1);
            pet_lib.setPetStatsByGrowth(baby, 1);
        }
        setObjVar(pcd, OBJVAR_GENDER, rand(0, 1) == 0 ? GENDER_MALE : GENDER_FEMALE);
        setObjVar(pcd, "pasture.bornAt", getGameTime());
        setMaster(baby, owner);
        pet_lib.savePetInfo(baby, pcd);
        if (!parkIntoPasture(baby, owner, pcd, spot))
        {
            // Keep the baby anyway, as a normal stored pet in the datapad.
            callable.setCDCallable(pcd, null);
            setObjVar(pcd, "pet.timeStored", getGameTime());
            destroyObject(baby);
            say(owner, "A newborn could not be put out to pasture; it is waiting in your datapad.");
            return null;
        }
        finishPasture(baby);
        return baby;
    }

    /** A wild baby of the species (tameable through the normal Tame option). */
    public static obj_id createWildBaby(String species, location spot) throws InterruptedException
    {
        obj_id baby = create.object(species, spot, true, false);
        if (!isIdValid(baby))
        {
            return null;
        }
        if (!hasScript(baby, "ai.pet_advance"))
        {
            // Same as create.maybeMakeBaby, without the random chance.
            attachScript(baby, "ai.pet_advance");
            String name = getAssignedName(baby);
            if (name != null && !name.equals("") && !name.equals("null"))
            {
                setName(baby, name + " (baby)");
            }
            else
            {
                setName(baby, getString(getNameStringId(baby)) + " (baby)");
            }
            setScale(baby, getScale(baby) * 0.4f);
        }
        return baby;
    }

    /** Mating part of "Pasture Info" (empty for males and the young). */
    public static String describeMating(obj_id animal) throws InterruptedException
    {
        if (!MATING_ENABLED || !canBreed(animal) || !isFemale(animal))
        {
            return "";
        }
        if (hasObjVar(animal, OBJVAR_MATE_DUE))
        {
            return " She is about to give birth.";
        }
        int left = getCooldownLeft(animal);
        if (left > 0)
        {
            return " She can have another baby in about " + ((left + 3599) / 3600) + " hour(s).";
        }
        int progress = hasObjVar(animal, OBJVAR_MATE_PROGRESS) ? getIntObjVar(animal, OBJVAR_MATE_PROGRESS) : 0;
        if (progress > 0)
        {
            return " Mating: " + Math.min(99, progress * 100 / MATE_TIME_SEC) + "% (a male stays within " + (int)MATE_RANGE + " m).";
        }
        return " She needs an adult male of her kind out to pasture within " + (int)MATE_RANGE + " m.";
    }

    /** Range the animal may roam from its pasture spot. */
    public static float getRoamRange(obj_id animal) throws InterruptedException
    {
        location home = getHome(animal);
        return (home != null && isIdValid(home.cell)) ? ROAM_RANGE_INDOOR : ROAM_RANGE;
    }
}
