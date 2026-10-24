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
 * Pastured animals never start a fight (aggro / alert trigger volumes are removed), but they can be
 * attacked and defend themselves; at 0 health they are downed, never killed, and recover after
 * INCAP_RECOVER_SEC, so no PCD can be lost to a kill.
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
    /**
     * false (owner decision 2026-10-24): pastured animals can be attacked and fight back, but like loose droids
     * they are only downed, never killed (ai.ai.OnIncapacitated skips pastured animals) and stand up again
     * after INCAP_RECOVER_SEC. They still never START a fight (aggro / alert volumes are removed).
     */
    public static final boolean PASTURE_INVULNERABLE = false;
    public static final float INCAP_RECOVER_SEC = 45.0f;

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

    // ---- growth while pastured -------------------------------------------------------------
    // A young animal (growth stage < 10) grows one stage every GROWTH_STEP_SEC it spends out to pasture, as an
    // alternative to taking it along to earn growth XP in combat. Progress is kept on the PCD (survives retrieve).
    // Combat growth (pet_lib.grantPetGrowthXp) is unchanged and still works when the pet is called out.
    public static final boolean GROWTH_ENABLED = true;
    public static final int GROWTH_STEP_SEC = 14400;              // 4 h per stage: baby (1) to adult (10) in 36 h
    public static final String OBJVAR_GROWTH_PROGRESS = "pasture.growth.progress";   // PCD: seconds towards the next stage
    public static final String OBJVAR_GROWTH_LAST = "pasture.growth.last";           // animal: game time of the last tick
    /** Interval of the pasture tick (growth + mating), see ai.pasture.handlePastureTick. */
    public static final float PASTURE_TICK_SEC = 300.0f;

    // ---- inheritance (breeding to improve) -------------------------------------------------
    // A baby inherits from BOTH parents: each inherited value is the average of the two parents times
    // (1 + a random modifier between BREED_MUTATION_MIN and BREED_MUTATION_MAX, plus up to
    // BREED_HANDLER_BONUS for the female owner's creature training skill). The modifier leans upward, so
    // pairing the best animals each generation improves the line. Works for tamed AND bio-engineered pets:
    // a parent without stored stats (a normal tamed pet) counts with the natural values of its species
    // at its level. Inherited: level, armour, crit chance, crit save, state resist, aggro bonus, size
    // (each mutated), colours, special attacks and ranged attack (taken from one parent at random).
    // Health, to-hit, defence and damage are written to match the inherited level, because on this server
    // pet_lib.setCraftedPetStatsByGrowth derives those four from the level (stat_balance) anyway.
    public static final float BREED_MUTATION_MIN = -0.05f;
    public static final float BREED_MUTATION_MAX = 0.10f;
    public static final float BREED_HANDLER_BONUS = 0.05f;          // at private_creature_training 100
    public static final int BREED_MAX_LEVEL = 90;
    public static final String SKILLMOD_BREEDING = "private_creature_training";
    public static final String OBJVAR_GENERATION = "pasture.generation";         // PCD: 0 = tamed / crafted, n = bred
    public static final String OBJVAR_MATE_GENES = "pasture.mate.genes";         // female: genes fixed at conception
    public static final String OBJVAR_WILD_GENES = "pasture.genes";              // wild baby: applied when it is tamed
    private static final String[] GENE_MUTATE_INT = { "level", "general_protection" };
    private static final String[] GENE_MUTATE_FLOAT = { "critChance", "critSave", "stateResist", "aggroBonus" };
    private static final String[] GENE_PICK_INT = { "hue", "specialAttack1", "specialAttack2" };
    private static final String[] GENE_PICK_STRING = { "rangedWeapon" };

    // ---- mating (part 2) ------------------------------------------------------------------
    // A pastured adult female with a pastured adult male of the same species within MATE_RANGE
    // collects "mating time" on every PASTURE_TICK_SEC tick; when MATE_TIME_SEC is reached she gives
    // birth to one baby. The baby is pastured for the FEMALE's owner when possible (owner loaded on
    // this server, below the pasture cap, datapad accepts a new control device); otherwise it is a
    // wild baby that must be tamed. Males may belong to anyone.
    public static final boolean MATING_ENABLED = true;
    public static final float MATE_RANGE = 20.0f;
    public static final int MATE_TIME_SEC = 3600;                 // 1 h near a male
    public static final int MATE_COOLDOWN_SEC = 86400;            // 24 h between births per female
    public static final int OWNER_WAIT_SEC = 21600;               // a due birth waits up to 6 h for the owner, then the baby is wild
    public static final String OBJVAR_MATE_PROGRESS = "pasture.mate.progress";   // animal: seconds collected
    public static final String OBJVAR_MATE_LAST = "pasture.mate.last";           // animal: game time of the last tick
    public static final String OBJVAR_MATE_DUE = "pasture.mate.due";             // animal: birth due since (waiting for the owner)
    public static final String OBJVAR_MATE_NEXT = "pasture.mate.nextAt";         // PCD: no new birth before this game time

    /** Shown in debug messages so a chat log proves which build runs. Bump on every change. */
    public static final String BUILD_TAG = "PASTURE-4";
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

    private static String nextStageText(obj_id pcd) throws InterruptedException
    {
        if (!GROWTH_ENABLED || !isIdValid(pcd))
        {
            return "";
        }
        int left = GROWTH_STEP_SEC - (hasObjVar(pcd, OBJVAR_GROWTH_PROGRESS) ? getIntObjVar(pcd, OBJVAR_GROWTH_PROGRESS) : 0);
        return ", next stage in about " + Math.max(1, (left + 1799) / 3600) + " h";
    }

    /** One-line status for the owner's "Pasture Info" radial entry. */
    public static String describe(obj_id animal) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(animal);
        int stage = getGrowthStage(pcd);
        String text = displayName(animal) + ": " + genderName(getPastureGender(pcd)) + ", " + (stage >= 10 ? "adult" : "young (growth " + stage + "/10" + nextStageText(pcd) + ")") + ", " + (getMode(animal) == MODE_STAY ? "staying put" : "roaming") + ".";
        int gen = isIdValid(pcd) ? getIntObjVar(pcd, OBJVAR_GENERATION) : 0;
        text += " Level " + parentLevel(pcd, getPastureSpecies(animal)) + (gen > 0 ? ", bred generation " + gen : "") + ".";
        return text + describeMating(animal);
    }

    // ---- growth -------------------------------------------------------------------------

    /** Seconds of a tick to count: the real time since the last one, never more than two ticks (restarts, lag). */
    private static int tickStep(obj_id animal, String lastVar) throws InterruptedException
    {
        int now = getGameTime();
        int last = hasObjVar(animal, lastVar) ? getIntObjVar(animal, lastVar) : now;
        setObjVar(animal, lastVar, now);
        int step = now - last;
        if (step <= 0)
        {
            return 0;
        }
        int maxStep = (int)(PASTURE_TICK_SEC * 2.0f);
        return step > maxStep ? (int)PASTURE_TICK_SEC : step;
    }

    /**
     * One growth tick for a pastured animal. Returns true when it grew a stage. Only the PCD and the
     * creature's size change: on this server a pet's combat stats come from its level, not its growth
     * stage (pet_lib.setCraftedPetStatsByGrowth), and that function must not run while the PCD is
     * parked (it would read the PCD as if no player owned it).
     */
    public static boolean growthTick(obj_id animal) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(animal);
        if (!GROWTH_ENABLED || !isPastured(animal) || !isIdValid(pcd) || !exists(pcd))
        {
            return false;
        }
        int step = tickStep(animal, OBJVAR_GROWTH_LAST);
        int stage = getGrowthStage(pcd);
        if (stage >= 10 || step <= 0 || isIncapacitated(animal))
        {
            return false;
        }
        int progress = (hasObjVar(pcd, OBJVAR_GROWTH_PROGRESS) ? getIntObjVar(pcd, OBJVAR_GROWTH_PROGRESS) : 0) + step;
        if (progress < GROWTH_STEP_SEC)
        {
            setObjVar(pcd, OBJVAR_GROWTH_PROGRESS, progress);
            return false;
        }
        stage++;
        if (stage >= 10)
        {
            stage = 10;
            removeObjVar(pcd, "ai.petAdvance.growthStage");
            removeObjVar(pcd, OBJVAR_GROWTH_PROGRESS);
        }
        else
        {
            setObjVar(pcd, "ai.petAdvance.growthStage", stage);
            setObjVar(pcd, OBJVAR_GROWTH_PROGRESS, progress - GROWTH_STEP_SEC);
        }
        applyGrowthScale(animal, pcd, stage);
        earnGrowthRewards(animal, pcd, stage);
        obj_id owner = getPastureOwner(animal);
        if (isIdValid(owner) && owner.isLoaded())
        {
            say(owner, stage >= 10 ? "Your pastured " + displayName(animal) + " is now fully grown." : "Your pastured " + displayName(animal) + " has grown (growth " + stage + "/10).");
        }
        return true;
    }

    /** Same size formula as pet_lib.setCraftedPetStatsByGrowth (30 % at stage 0, full size at stage 10). */
    public static void applyGrowthScale(obj_id animal, obj_id pcd, int stage) throws InterruptedException
    {
        dictionary creatureDict = utils.dataTableGetRow(create.CREATURE_TABLE, getCreatureName(animal));
        float baseScale = utils.hasScriptVar(animal, "ai.baseScale") ? utils.getFloatScriptVar(animal, "ai.baseScale") : 1.0f;
        float maxScale = 9999.0f;
        float scaleAdjust = 1.0f;
        if (hasObjVar(pcd, "creature_attribs.scale"))
        {
            scaleAdjust = getFloatObjVar(pcd, "creature_attribs.scale");
        }
        else if (creatureDict != null)
        {
            maxScale = creatureDict.getFloat("maxScale");
            scaleAdjust = (creatureDict.getFloat("minScale") + maxScale) / 2.0f;
        }
        float realScale = baseScale * scaleAdjust;
        float babyScale = realScale * 0.3f;
        float finalScale = babyScale + ((realScale - babyScale) / 10.0f) * Math.max(1, Math.min(10, stage));
        if (finalScale > maxScale)
        {
            finalScale = maxScale;
        }
        if (finalScale > 0.0f)
        {
            setScale(animal, finalScale);
        }
    }

    /** The training rewards pet_lib.grantPetGrowthXp gives per stage, written to the PCD only. */
    private static void earnGrowthRewards(obj_id animal, obj_id pcd, int stage) throws InterruptedException
    {
        int toBeEarned = getIntObjVar(pcd, "ai.petAbility.toBeEarned");
        int available = getIntObjVar(pcd, "ai.petAbility.available");
        if (toBeEarned > 0 && rand(0, 100) < stage * 10)
        {
            setObjVar(pcd, "ai.petAbility.toBeEarned", toBeEarned - 1);
            setObjVar(pcd, "ai.petAbility.available", ++available);
            toBeEarned--;
        }
        if (available > 0 || toBeEarned > 0)
        {
            int newPts = (int)((getLevel(animal) + 55) / 8.0f + 0.5f);
            setObjVar(pcd, "ai.petAbility.trainingPts", getIntObjVar(pcd, "ai.petAbility.trainingPts") + newPts);
        }
    }

    // ---- genetics -----------------------------------------------------------------------

    private static dictionary statRow(int level) throws InterruptedException
    {
        return dataTableGetRow(pet_lib.TBL_MOB_STAT_BALANCE, Math.max(1, Math.min(BREED_MAX_LEVEL, level)) - 1);
    }

    /** A parent's level: stored (bio-engineered / bred) or the natural level of its species. */
    private static int parentLevel(obj_id pcd, String species) throws InterruptedException
    {
        if (isIdValid(pcd) && hasObjVar(pcd, "creature_attribs.level"))
        {
            int l = getIntObjVar(pcd, "creature_attribs.level");
            if (l > 0)
            {
                return l;
            }
        }
        return Math.max(1, create.calcCreatureLevel(species));
    }

    /** A parent's integer stat: stored, or the natural value (stat_balance at its level). */
    private static float parentInt(obj_id pcd, String key, int level) throws InterruptedException
    {
        if (isIdValid(pcd) && hasObjVar(pcd, "creature_attribs." + key))
        {
            return getIntObjVar(pcd, "creature_attribs." + key);
        }
        if (key.equals("level"))
        {
            return level;
        }
        dictionary row = statRow(level);
        if (row != null && key.equals("general_protection"))
        {
            return row.getInt("Armor");
        }
        return 0.0f;
    }

    private static float parentFloat(obj_id pcd, String key) throws InterruptedException
    {
        return (isIdValid(pcd) && hasObjVar(pcd, "creature_attribs." + key)) ? getFloatObjVar(pcd, "creature_attribs." + key) : 0.0f;
    }

    private static float parentScale(obj_id pcd, dictionary creatureDict) throws InterruptedException
    {
        if (isIdValid(pcd) && hasObjVar(pcd, "creature_attribs.scale"))
        {
            return getFloatObjVar(pcd, "creature_attribs.scale");
        }
        return creatureDict == null ? 1.0f : (creatureDict.getFloat("minScale") + creatureDict.getFloat("maxScale")) / 2.0f;
    }

    private static float mutation(float handlerBonus)
    {
        return 1.0f + BREED_MUTATION_MIN + rand() * (BREED_MUTATION_MAX - BREED_MUTATION_MIN) + handlerBonus;
    }

    /**
     * Fixes the baby's genes at conception (stored on the female under OBJVAR_MATE_GENES), so the birth does
     * not depend on the father still being there. owner = the female's owner (for the handler bonus).
     */
    public static void conceive(obj_id female, obj_id male) throws InterruptedException
    {
        removeObjVar(female, OBJVAR_MATE_GENES);
        obj_id mPcd = getParkedPcd(female);
        obj_id fPcd = getParkedPcd(male);
        String species = getPastureSpecies(female);
        dictionary creatureDict = utils.dataTableGetRow(create.CREATURE_TABLE, species);
        obj_id owner = getPastureOwner(female);
        float bonus = 0.0f;
        if (isIdValid(owner) && owner.isLoaded() && exists(owner))
        {
            bonus = Math.min(100, Math.max(0, getSkillStatisticModifier(owner, SKILLMOD_BREEDING))) / 100.0f * BREED_HANDLER_BONUS;
        }
        String g = OBJVAR_MATE_GENES + ".";
        int mLevel = parentLevel(mPcd, species);
        int fLevel = parentLevel(fPcd, species);
        int level = Math.round((mLevel + fLevel) / 2.0f * mutation(bonus));
        level = Math.max(1, Math.min(BREED_MAX_LEVEL, level));
        setObjVar(female, g + "level", level);
        for (String key : GENE_MUTATE_INT)
        {
            if (key.equals("level"))
            {
                continue;
            }
            float v = (parentInt(mPcd, key, mLevel) + parentInt(fPcd, key, fLevel)) / 2.0f * mutation(bonus);
            setObjVar(female, g + key, Math.max(0, Math.min(10000, Math.round(v))));
        }
        for (String key : GENE_MUTATE_FLOAT)
        {
            float a = parentFloat(mPcd, key);
            float b = parentFloat(fPcd, key);
            if (a <= 0.0f && b <= 0.0f)
            {
                continue;
            }
            setObjVar(female, g + key, Math.max(0.0f, Math.min(100.0f, (a + b) / 2.0f * mutation(bonus))));
        }
        float attackSpeed = (parentFloat(mPcd, "attackSpeed") + parentFloat(fPcd, "attackSpeed")) / 2.0f;
        if (attackSpeed > 0.0f)
        {
            setObjVar(female, g + "attackSpeed", attackSpeed);
        }
        // Size: small random drift around the parents' average, kept within the species' range.
        float scale = (parentScale(mPcd, creatureDict) + parentScale(fPcd, creatureDict)) / 2.0f * (1.0f + (rand() * 0.1f - 0.05f));
        if (creatureDict != null)
        {
            scale = Math.max(creatureDict.getFloat("minScale") * 0.8f, Math.min(creatureDict.getFloat("maxScale") * 1.2f, scale));
        }
        setObjVar(female, g + "scale", scale);
        // Traits taken whole from one parent.
        obj_id[] parents = { mPcd, fPcd };
        for (String key : GENE_PICK_INT)
        {
            obj_id from = parents[rand(0, 1)];
            if (!hasObjVar(from, "creature_attribs." + key))
            {
                from = parents[0] == from ? parents[1] : parents[0];
            }
            if (isIdValid(from) && hasObjVar(from, "creature_attribs." + key))
            {
                setObjVar(female, g + key, getIntObjVar(from, "creature_attribs." + key));
            }
        }
        for (String key : GENE_PICK_STRING)
        {
            obj_id from = parents[rand(0, 1)];
            String v = isIdValid(from) ? getStringObjVar(from, "creature_attribs." + key) : null;
            if (v != null && v.length() > 0)
            {
                setObjVar(female, g + key, v);
            }
        }
        // Colours: the whole palette of one parent.
        obj_id colourFrom = parents[rand(0, 1)];
        obj_var_list ovl = isIdValid(colourFrom) ? getObjVarList(colourFrom, pet_lib.VAR_PALVAR_VARS) : null;
        if (ovl != null)
        {
            for (int i = 0; i < ovl.getNumItems(); i++)
            {
                obj_var ov = ovl.getObjVar(i);
                setObjVar(female, g + "palvar." + ov.getName(), ov.getIntData());
            }
        }
        int gen = Math.max(getIntObjVar(mPcd, OBJVAR_GENERATION), getIntObjVar(fPcd, OBJVAR_GENERATION)) + 1;
        setObjVar(female, g + "generation", gen);
        dbg(owner, "conceive: " + female + " x " + male + " level " + mLevel + "/" + fLevel + " -> " + level + " gen " + gen);
    }

    /** Copies genes stored under prefix on holder into the PCD (creature_attribs.*, palette, generation). */
    public static void applyGenes(obj_id holder, String prefix, obj_id pcd) throws InterruptedException
    {
        String g = prefix + ".";
        if (!isIdValid(holder) || !isIdValid(pcd) || !hasObjVar(holder, g + "level"))
        {
            return;
        }
        int level = getIntObjVar(holder, g + "level");
        setObjVar(pcd, "creature_attribs.level", level);
        // Stats the server derives from the level anyway: keep them consistent with it.
        dictionary row = statRow(level);
        if (row != null)
        {
            float dps = row.getFloat("damagePerSecond");
            setObjVar(pcd, "creature_attribs." + create.MAXATTRIBNAMES[HEALTH], row.getInt("HP"));
            setObjVar(pcd, "creature_attribs." + create.MAXATTRIBNAMES[CONSTITUTION], row.getInt("HealthRegen"));
            setObjVar(pcd, "creature_attribs.toHitChance", row.getInt("ToHit"));
            setObjVar(pcd, "creature_attribs.defenseValue", row.getInt("Def"));
            setObjVar(pcd, "creature_attribs.minDamage", (int)dps);
            setObjVar(pcd, "creature_attribs.maxDamage", (int)dps * 3);
        }
        for (String key : GENE_MUTATE_INT)
        {
            if (!key.equals("level") && hasObjVar(holder, g + key))
            {
                setObjVar(pcd, "creature_attribs." + key, getIntObjVar(holder, g + key));
            }
        }
        for (String key : GENE_MUTATE_FLOAT)
        {
            setObjVar(pcd, "creature_attribs." + key, hasObjVar(holder, g + key) ? getFloatObjVar(holder, g + key) : 0.0f);
        }
        String[] floats = { "attackSpeed", "scale" };
        for (String key : floats)
        {
            if (hasObjVar(holder, g + key))
            {
                setObjVar(pcd, "creature_attribs." + key, getFloatObjVar(holder, g + key));
            }
        }
        for (String key : GENE_PICK_INT)
        {
            if (hasObjVar(holder, g + key))
            {
                setObjVar(pcd, "creature_attribs." + key, getIntObjVar(holder, g + key));
            }
        }
        for (String key : GENE_PICK_STRING)
        {
            if (hasObjVar(holder, g + key))
            {
                setObjVar(pcd, "creature_attribs." + key, getStringObjVar(holder, g + key));
            }
        }
        obj_var_list ovl = getObjVarList(holder, g + "palvar");
        if (ovl != null && ovl.getNumItems() > 0)
        {
            removeObjVar(pcd, pet_lib.VAR_PALVAR_BASE);
            for (int i = 0; i < ovl.getNumItems(); i++)
            {
                obj_var ov = ovl.getObjVar(i);
                setObjVar(pcd, pet_lib.VAR_PALVAR_VARS + "." + ov.getName(), ov.getIntData());
            }
        }
        setObjVar(pcd, OBJVAR_GENERATION, getIntObjVar(holder, g + "generation"));
    }

    /** Moves genes from one holder to another (female at birth -> wild baby, applied later when it is tamed). */
    public static void moveGenes(obj_id from, String fromPrefix, obj_id to, String toPrefix) throws InterruptedException
    {
        obj_var_list ovl = getObjVarList(from, fromPrefix);
        if (ovl == null)
        {
            return;
        }
        for (int i = 0; i < ovl.getNumItems(); i++)
        {
            obj_var ov = ovl.getObjVar(i);
            Object data = ov.getData();
            String name = toPrefix + "." + ov.getName();
            if (data instanceof Integer)
            {
                setObjVar(to, name, ((Integer)data).intValue());
            }
            else if (data instanceof Float)
            {
                setObjVar(to, name, ((Float)data).floatValue());
            }
            else if (data instanceof String)
            {
                setObjVar(to, name, (String)data);
            }
        }
        obj_var_list pal = getObjVarList(from, fromPrefix + ".palvar");
        if (pal != null)
        {
            for (int i = 0; i < pal.getNumItems(); i++)
            {
                obj_var ov = pal.getObjVar(i);
                setObjVar(to, toPrefix + ".palvar." + ov.getName(), ov.getIntData());
                hue.setColor(to, ov.getName(), ov.getIntData());
            }
        }
    }

    /**
     * Hook for pet_lib.makeControlDevice: a wild baby born in a pasture carries its parents' genes until it
     * is tamed; they go onto its new control device.
     */
    public static void applyWildGenesOnTame(obj_id pet, obj_id pcd) throws InterruptedException
    {
        if (isIdValid(pet) && hasObjVar(pet, OBJVAR_WILD_GENES + ".level"))
        {
            applyGenes(pet, OBJVAR_WILD_GENES, pcd);
            removeObjVar(pet, OBJVAR_WILD_GENES);
        }
    }

    /** Re-applies stats, size and colours from the PCD while it is still in the owner's datapad. */
    public static void applyPcdLook(obj_id animal, obj_id pcd) throws InterruptedException
    {
        pet_lib.restoreCustomization(animal, pcd);
        pet_lib.setCraftedPetStatsByGrowth(pcd, animal, getGrowthStage(pcd));
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
     * One mating tick for a pastured female (called by ai.pasture every PASTURE_TICK_SEC).
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
        int step = tickStep(female, OBJVAR_MATE_LAST);
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
        int progress = (hasObjVar(female, OBJVAR_MATE_PROGRESS) ? getIntObjVar(female, OBJVAR_MATE_PROGRESS) : 0) + step;
        setObjVar(female, OBJVAR_MATE_PROGRESS, progress);
        dbg(owner, "mate: " + female + " with male " + male + " progress " + progress + "/" + MATE_TIME_SEC);
        if (progress < MATE_TIME_SEC)
        {
            return false;
        }
        removeObjVar(female, OBJVAR_MATE_PROGRESS);
        conceive(female, male);
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
            baby = createOwnedBaby(female, species, spot, owner);
            owned = isIdValid(baby);
        }
        if (!owned)
        {
            baby = createWildBaby(female, species, spot);
        }
        removeObjVar(female, OBJVAR_MATE_DUE);
        removeObjVar(female, OBJVAR_MATE_GENES);
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
    public static obj_id createOwnedBaby(obj_id mother, String species, location spot, obj_id owner) throws InterruptedException
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
        // Inherited stats, size and colours (fixed at conception), applied while the PCD is in the datapad.
        applyGenes(mother, OBJVAR_MATE_GENES, pcd);
        applyPcdLook(baby, pcd);
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
    public static obj_id createWildBaby(obj_id mother, String species, location spot) throws InterruptedException
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
        // Genes travel with the wild baby and go onto its control device when it is tamed.
        if (isIdValid(mother) && hasObjVar(mother, OBJVAR_MATE_GENES + ".level"))
        {
            moveGenes(mother, OBJVAR_MATE_GENES, baby, OBJVAR_WILD_GENES);
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

    /** The ship whose cell holds this animal's pasture spot, or null when the spot is not inside a ship. */
    public static obj_id getHomeShip(obj_id animal) throws InterruptedException
    {
        if (!isPastured(animal) || !hasObjVar(animal, OBJVAR_HOME))
        {
            return null;
        }
        location home = getLocationObjVar(animal, OBJVAR_HOME);
        if (home == null || !isIdValid(home.cell))
        {
            return null;
        }
        obj_id ship = getContainedBy(home.cell);
        if (!isIdValid(ship) || space_transition.getContainingShip(home.cell) != ship)
        {
            return null;
        }
        return ship;
    }

    /** An animal pastured inside a ship only fights things aboard the same ship (never chases out through the hull). */
    public static boolean mayEngage(obj_id animal, obj_id target) throws InterruptedException
    {
        obj_id ship = getHomeShip(animal);
        if (!isIdValid(ship))
        {
            return true;
        }
        return isIdValid(target) && space_transition.getContainingShip(target) == ship;
    }

    /** Range the animal may roam from its pasture spot. */
    public static float getRoamRange(obj_id animal) throws InterruptedException
    {
        location home = getHome(animal);
        return (home != null && isIdValid(home.cell)) ? ROAM_RANGE_INDOOR : ROAM_RANGE;
    }
}
