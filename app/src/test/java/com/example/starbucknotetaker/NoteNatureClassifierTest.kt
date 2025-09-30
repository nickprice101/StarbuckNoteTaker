package com.example.starbucknotetaker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteNatureClassifierTest {

    private val classifier = NoteNatureClassifier()

    @Test
    fun classifyPersonalDailyLife() = runBlocking {
        val text = """
            Weekend plans:
            - Brunch with friends at 11am
            - Errands and chores around the apartment
            - Movie night celebration with family tonight
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.PERSONAL_DAILY_LIFE, label.type)
        assertEquals(NoteNatureType.PERSONAL_DAILY_LIFE.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyFinanceLegal() = runBlocking {
        val text = """
            Q1 budget review
            - Invoice 1042 due March 15 ($1,200)
            - Update contract clause 4 with compliance notes
            - Track expense reimbursements and insurance policy updates
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.FINANCE_LEGAL, label.type)
        assertEquals(NoteNatureType.FINANCE_LEGAL.humanReadable, label.humanReadable)
    }

    @Test
    fun classifySelfImprovement() = runBlocking {
        val text = """
            Personal growth goals
            [ ] Morning meditation habit tracker
            [x] Practice guitar 30 minutes
            Weekly goals: Reflect on progress and write affirmation.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.SELF_IMPROVEMENT, label.type)
        assertEquals(NoteNatureType.SELF_IMPROVEMENT.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyHealthWellness() = runBlocking {
        val text = """
            Health log
            Workout: 5km run + 30 minutes yoga
            Breakfast: oats and berries (320 calories)
            Medication: vitamin d dose 2000mg nightly
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.HEALTH_WELLNESS, label.type)
        assertEquals(NoteNatureType.HEALTH_WELLNESS.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyEducationLearning() = runBlocking {
        val text = """
            Lecture notes - Biology 201
            Lesson 3: Cell structures
            Key concepts: mitochondria, ribosomes
            Homework assignment: read chapter 4 and complete practice problems.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.EDUCATION_LEARNING, label.type)
        assertEquals(NoteNatureType.EDUCATION_LEARNING.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyHomeFamily() = runBlocking {
        val text = """
            Family schedule
            - School dropoff 8:00am for kids
            - Laundry and house cleaning before grandparents arrive
            Call babysitter to confirm Friday night.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.HOME_FAMILY, label.type)
        assertEquals(NoteNatureType.HOME_FAMILY.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyMeetingRecap() = runBlocking {
        val text = """
            Team Meeting Recap
            Attendees: Alex, Priya, Jordan
            Agenda:
            - Timeline updates
            - Security review
            Action items: Follow up with vendor by Friday.
        """.trimIndent()
        val event = NoteEvent(
            start = 0L,
            end = 45 * 60 * 1000L,
            allDay = false,
            timeZone = "UTC",
            location = "Conference Room"
        )

        val label = classifier.classify(text, event)

        assertEquals(NoteNatureType.MEETING_RECAP, label.type)
        assertEquals(NoteNatureType.MEETING_RECAP.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyShoppingList() = runBlocking {
        val text = """
            Groceries to buy:
            - milk
            - eggs
            - bread
            Need to pick up coffee beans too.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.SHOPPING_LIST, label.type)
        assertEquals("Shopping list with 4 items", label.humanReadable)
    }

    @Test
    fun classifyReminder() = runBlocking {
        val text = "Reminder: Submit the quarterly report by Friday at 4pm. Don't forget to email Sam afterwards!"
        val event = NoteEvent(
            start = 1_700_000_000_000L,
            end = 1_700_000_900_000L,
            allDay = false,
            timeZone = "UTC",
            reminderMinutesBeforeStart = 30
        )

        val label = classifier.classify(text, event)

        assertEquals(NoteNatureType.REMINDER, label.type)
        assertEquals(NoteNatureType.REMINDER.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyJournalEntry() = runBlocking {
        val text = "Today was calm and reflective. I am grateful for the quiet morning and I felt proud finishing my project."

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.JOURNAL_ENTRY, label.type)
        assertEquals(NoteNatureType.JOURNAL_ENTRY.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyTravelPlan() = runBlocking {
        val text = """
            Tokyo itinerary:
            Flight number JL005 departure Monday 10:30am, arrival 14:50.
            Hotel booking at Park Hyatt, check-in 3pm, checkout Friday morning.
            Packing list: passport, adapter, camera.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.TRAVEL_PLAN, label.type)
        assertEquals(NoteNatureType.TRAVEL_PLAN.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyProjectManagement() = runBlocking {
        val text = """
            Website redesign project plan
            Milestone 1: finalize wireframes (owner: Priya, due Apr 15)
            Milestone 2: sprint planning for development backlog
            Deliverables: updated roadmap, stakeholder status update on launch timeline.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.PROJECT_MANAGEMENT, label.type)
        assertEquals(NoteNatureType.PROJECT_MANAGEMENT.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyEventPlanning() = runBlocking {
        val text = """
            Wedding celebration checklist
            Guest list: 120 RSVP responses confirmed for June 22
            Vendor outreach for catering, photographer, and venue walkthrough
            Build day-of timeline and seating chart playlist notes.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.EVENT_PLANNING, label.type)
        assertEquals(NoteNatureType.EVENT_PLANNING.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyFoodRecipe() = runBlocking {
        val text = """
            Lemon pasta recipe (serves 4)
            Ingredients:
            - 12 oz spaghetti
            - 2 tablespoons olive oil
            - 1 teaspoon lemon zest
            Instructions: Preheat skillet, sauté garlic, mix pasta and simmer sauce.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.FOOD_RECIPE, label.type)
        assertEquals(NoteNatureType.FOOD_RECIPE.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyCreativeWriting() = runBlocking {
        val text = """
            Chapter 3 outline - "The Storm"
            Characters: Mira (protagonist), Rian (antagonist)
            Scene 1: Dialogue at the docks reveals the central plot twist.
            Theme notes and world building details for the novel draft.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.CREATIVE_WRITING, label.type)
        assertEquals(NoteNatureType.CREATIVE_WRITING.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyTechnicalReference() = runBlocking {
        val text = """
            API outage troubleshooting log
            Steps to reproduce:
            ${'$'} curl -X GET https://api.example.com/v1/status
            Observed error code 503 with stack trace in server logs.
            Plan: update deployment config and rollback if issue persists.
        """.trimIndent()

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.TECHNICAL_REFERENCE, label.type)
        assertEquals(NoteNatureType.TECHNICAL_REFERENCE.humanReadable, label.humanReadable)
    }

    @Test
    fun classifyFallbackForAmbiguousNote() = runBlocking {
        val text = "Ideas and scribbles"

        val label = classifier.classify(text, null)

        assertEquals(NoteNatureType.GENERAL_NOTE, label.type)
        assertEquals(NoteNatureType.GENERAL_NOTE.humanReadable, label.humanReadable)
    }
}
