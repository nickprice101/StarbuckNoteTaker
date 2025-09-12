class NoteTaker {
    constructor() {
        this.notes = this.loadNotes();
        this.currentNoteId = null;
        this.untitledCounter = this.getNextUntitledCounter();
        this.initializeEventListeners();
        this.renderNotes();
    }

    initializeEventListeners() {
        // Search functionality
        document.getElementById('searchInput').addEventListener('input', (e) => {
            this.searchNotes(e.target.value);
        });

        document.getElementById('clearSearch').addEventListener('click', () => {
            document.getElementById('searchInput').value = '';
            this.searchNotes('');
        });

        // Add new note
        document.getElementById('addNoteBtn').addEventListener('click', () => {
            this.openNoteEditor();
        });

        // Modal controls
        document.getElementById('closeModal').addEventListener('click', () => {
            this.closeNoteEditor();
        });

        document.getElementById('saveNoteBtn').addEventListener('click', () => {
            this.saveNote();
        });

        document.getElementById('deleteNoteBtn').addEventListener('click', () => {
            this.deleteNote();
        });

        // Editor toolbar
        document.getElementById('addLinkBtn').addEventListener('click', () => {
            this.addLink();
        });

        document.getElementById('addImageBtn').addEventListener('click', () => {
            document.getElementById('imageUpload').click();
        });

        document.getElementById('imageUpload').addEventListener('change', (e) => {
            this.handleImageUpload(e);
        });

        // Close modal when clicking outside
        document.getElementById('noteModal').addEventListener('click', (e) => {
            if (e.target === document.getElementById('noteModal')) {
                this.closeNoteEditor();
            }
        });

        // Paste event for images
        document.getElementById('noteContent').addEventListener('paste', (e) => {
            this.handlePaste(e);
        });
    }

    loadNotes() {
        const stored = localStorage.getItem('starbuck-notes');
        return stored ? JSON.parse(stored) : [];
    }

    saveNotes() {
        localStorage.setItem('starbuck-notes', JSON.stringify(this.notes));
    }

    getNextUntitledCounter() {
        const untitledNotes = this.notes.filter(note => 
            note.title.startsWith('Untitled ')
        );
        return untitledNotes.length + 1;
    }

    generateId() {
        return Date.now().toString(36) + Math.random().toString(36).substr(2);
    }

    openNoteEditor(noteId = null) {
        this.currentNoteId = noteId;
        const modal = document.getElementById('noteModal');
        const titleInput = document.getElementById('noteTitle');
        const contentInput = document.getElementById('noteContent');
        const deleteBtn = document.getElementById('deleteNoteBtn');

        if (noteId) {
            const note = this.notes.find(n => n.id === noteId);
            titleInput.value = note.title;
            contentInput.value = note.content;
            deleteBtn.style.display = 'inline-block';
        } else {
            titleInput.value = `Untitled ${this.untitledCounter}`;
            contentInput.value = '';
            deleteBtn.style.display = 'none';
        }

        modal.style.display = 'block';
        titleInput.focus();
    }

    closeNoteEditor() {
        document.getElementById('noteModal').style.display = 'none';
        this.currentNoteId = null;
    }

    saveNote() {
        const title = document.getElementById('noteTitle').value.trim();
        const content = document.getElementById('noteContent').value.trim();

        if (!title) {
            alert('Please enter a title for your note.');
            return;
        }

        const now = new Date();

        if (this.currentNoteId) {
            // Update existing note
            const noteIndex = this.notes.findIndex(n => n.id === this.currentNoteId);
            this.notes[noteIndex] = {
                ...this.notes[noteIndex],
                title,
                content,
                updatedAt: now.toISOString()
            };
        } else {
            // Create new note
            const newNote = {
                id: this.generateId(),
                title,
                content,
                createdAt: now.toISOString(),
                updatedAt: now.toISOString()
            };
            this.notes.unshift(newNote);
            
            // Update untitled counter if using default title
            if (title.startsWith('Untitled ')) {
                this.untitledCounter = this.getNextUntitledCounter();
            }
        }

        this.saveNotes();
        this.renderNotes();
        this.closeNoteEditor();
    }

    deleteNote() {
        if (!this.currentNoteId) return;

        if (confirm('Are you sure you want to delete this note?')) {
            this.notes = this.notes.filter(n => n.id !== this.currentNoteId);
            this.saveNotes();
            this.renderNotes();
            this.closeNoteEditor();
        }
    }

    addLink() {
        const url = prompt('Enter the URL:');
        if (url) {
            const text = prompt('Enter link text (optional):') || url;
            const linkMarkdown = `[${text}](${url})`;
            this.insertTextAtCursor(linkMarkdown);
        }
    }

    handleImageUpload(event) {
        const file = event.target.files[0];
        if (file && file.type.startsWith('image/')) {
            const reader = new FileReader();
            reader.onload = (e) => {
                const imageMarkdown = `![Image](${e.target.result})`;
                this.insertTextAtCursor(imageMarkdown);
            };
            reader.readAsDataURL(file);
        }
    }

    handlePaste(event) {
        const items = event.clipboardData.items;
        for (let item of items) {
            if (item.type.startsWith('image/')) {
                event.preventDefault();
                const file = item.getAsFile();
                const reader = new FileReader();
                reader.onload = (e) => {
                    const imageMarkdown = `![Pasted Image](${e.target.result})`;
                    this.insertTextAtCursor(imageMarkdown);
                };
                reader.readAsDataURL(file);
                break;
            }
        }
    }

    insertTextAtCursor(text) {
        const textarea = document.getElementById('noteContent');
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        const before = textarea.value.substring(0, start);
        const after = textarea.value.substring(end);
        textarea.value = before + text + after;
        textarea.selectionStart = textarea.selectionEnd = start + text.length;
        textarea.focus();
    }

    renderNotes(filteredNotes = null) {
        const container = document.getElementById('notesContainer');
        const notesToRender = filteredNotes || this.getSortedNotes();

        if (notesToRender.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <p>No notes found.</p>
                    <p>Click "Add New Note" to get started!</p>
                </div>
            `;
            return;
        }

        container.innerHTML = notesToRender.map(note => `
            <div class="note-card" onclick="noteTaker.openNoteEditor('${note.id}')">
                <div class="note-title">${this.escapeHtml(note.title)}</div>
                <div class="note-date">${this.formatDate(note.updatedAt || note.createdAt)}</div>
                <div class="note-preview">${this.renderPreview(note.content)}</div>
            </div>
        `).join('');
    }

    getSortedNotes() {
        return [...this.notes].sort((a, b) => {
            const dateA = new Date(a.updatedAt || a.createdAt);
            const dateB = new Date(b.updatedAt || b.createdAt);
            return dateB - dateA; // Newest first
        });
    }

    searchNotes(query) {
        if (!query.trim()) {
            this.renderNotes();
            return;
        }

        const filtered = this.notes.filter(note => {
            const searchText = (note.title + ' ' + note.content).toLowerCase();
            return searchText.includes(query.toLowerCase());
        });

        this.renderNotes(filtered);
    }

    renderPreview(content) {
        if (!content) return '<em>No content</em>';
        
        // Convert markdown-style links and images to HTML for preview
        let preview = content
            .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" onclick="event.stopPropagation(); window.open(\'$2\', \'_blank\')">$1</a>')
            .replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width: 100px; max-height: 100px;" onclick="event.stopPropagation();">');
        
        // Truncate long content
        if (preview.length > 150) {
            preview = preview.substring(0, 150) + '...';
        }
        
        return preview;
    }

    formatDate(dateString) {
        const date = new Date(dateString);
        const now = new Date();
        const diffTime = Math.abs(now - date);
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays === 1) {
            return 'Today, ' + date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
        } else if (diffDays === 2) {
            return 'Yesterday, ' + date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
        } else if (diffDays <= 7) {
            return `${diffDays - 1} days ago`;
        } else {
            return date.toLocaleDateString();
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

// Initialize the application
let noteTaker;
document.addEventListener('DOMContentLoaded', () => {
    noteTaker = new NoteTaker();
});