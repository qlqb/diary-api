import React, { useState } from 'react';
import { Plus, Calendar, BookOpen, Sparkles, Search, X, LogOut, User, Mail, Lock } from 'lucide-react';

export default function PrismaticDiary() {
const [user, setUser] = useState(null);
const [authMode, setAuthMode] = useState('login');
const [authForm, setAuthForm] = useState({ email: '', password: '', name: '' });
const [entries, setEntries] = useState([
{
id: 1,
date: '2026-01-15',
title: '새로운 시작',
content: '오늘은 정말 특별한 하루였다. 아침 햇살이 유난히 따뜻했고...',
mood: 'happy'
},
{
id: 2,
date: '2026-01-14',
title: '카페에서의 여유',
content: '좋아하는 카페에서 책을 읽으며 오후를 보냈다. 창밖으로 보이는 풍경이 평화로웠다.',
mood: 'neutral'
}
]);
const [showEditor, setShowEditor] = useState(false);
const [currentEntry, setCurrentEntry] = useState({ title: '', content: '', mood: 'neutral' });
const [searchQuery, setSearchQuery] = useState('');

const moodColors = {
happy: 'from-yellow-400 via-pink-400 to-purple-500',
neutral: 'from-blue-400 via-cyan-400 to-teal-500',
sad: 'from-indigo-400 via-purple-400 to-pink-500',
excited: 'from-orange-400 via-red-400 to-pink-500'
};

const handleAuth = () => {
if (authMode === 'login') {
if (authForm.email && authForm.password) {
setUser({ email: authForm.email, name: authForm.email.split('@')[0] });
setAuthForm({ email: '', password: '', name: '' });
}
} else {
if (authForm.email && authForm.password && authForm.name) {
setUser({ email: authForm.email, name: authForm.name });
setAuthForm({ email: '', password: '', name: '' });
}
}
};

const handleLogout = () => {
setUser(null);
setSearchQuery('');
setShowEditor(false);
};

const handleSave = () => {
if (currentEntry.title && currentEntry.content) {
setEntries([
{
id: Date.now(),
date: new Date().toISOString().split('T')[0],
...currentEntry
},
...entries
]);
setCurrentEntry({ title: '', content: '', mood: 'neutral' });
setShowEditor(false);
}
};

const filteredEntries = entries.filter(entry => {
if (!searchQuery) return true;
const query = searchQuery.toLowerCase();
return (
entry.title.toLowerCase().includes(query) ||
entry.content.toLowerCase().includes(query) ||
entry.date.includes(query)
);
});

const highlightText = (text, query) => {
if (!query) return text;
const parts = text.split(new RegExp(`(${query})`, 'gi'));
return parts.map((part, i) =>
part.toLowerCase() === query.toLowerCase()
? <mark key={i} className="bg-yellow-400/40 text-yellow-100 px-1 rounded">{part}</mark>
: part
);
};

// Login/Signup Screen
if (!user) {
return (
<div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 p-6 flex items-center justify-center">
    {/* Prismatic Background Effects */}
    <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/4 w-96 h-96 bg-gradient-to-br from-purple-500/20 to-pink-500/20 rounded-full blur-3xl animate-pulse"></div>
        <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-gradient-to-br from-cyan-500/20 to-blue-500/20 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }}></div>
    <div className="absolute top-1/2 left-1/2 w-96 h-96 bg-gradient-to-br from-yellow-500/10 to-orange-500/10 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '2s' }}></div>
</div>

<div className="w-full max-w-md relative z-10">
    {/* Logo */}
    <div className="text-center mb-8">
        <div className="inline-flex items-center gap-3 mb-4">
            <BookOpen className="w-12 h-12" strokeWidth={1.5} stroke="url(#gradient1)" />
            <svg width="0" height="0">
                <defs>
                    <linearGradient id="gradient1" x1="0%" y1="0%" x2="100%" y2="100%">
                        <stop offset="0%" stopColor="#a855f7" />
                        <stop offset="50%" stopColor="#ec4899" />
                        <stop offset="100%" stopColor="#f59e0b" />
                    </linearGradient>
                </defs>
            </svg>
            <h1 className="text-4xl font-bold bg-gradient-to-r from-purple-400 via-pink-400 to-yellow-400 bg-clip-text text-transparent">
                Prismatic Diary
            </h1>
        </div>
        <p className="text-purple-200/80">매일의 순간을 빛나는 기억으로</p>
    </div>

    {/* Auth Form */}
    <div className="p-8 rounded-3xl bg-gradient-to-br from-white/10 to-white/5 backdrop-blur-2xl border border-white/20 shadow-2xl">
        <div className="flex gap-2 mb-6">
            <button
                    onClick={() => setAuthMode('login')}
            className={`flex-1 py-3 px-6 rounded-xl font-semibold transition-all duration-300 ${
            authMode === 'login'
            ? 'bg-gradient-to-r from-purple-500 to-pink-500 text-white shadow-lg'
            : 'bg-white/5 text-purple-200 hover:bg-white/10'
            }`}
            >
            로그인
            </button>
            <button
                    onClick={() => setAuthMode('signup')}
            className={`flex-1 py-3 px-6 rounded-xl font-semibold transition-all duration-300 ${
            authMode === 'signup'
            ? 'bg-gradient-to-r from-purple-500 to-pink-500 text-white shadow-lg'
            : 'bg-white/5 text-purple-200 hover:bg-white/10'
            }`}
            >
            회원가입
            </button>
        </div>

        <div className="space-y-4">
            {authMode === 'signup' && (
            <div className="relative">
                <User className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-purple-300" />
                <input
                        type="text"
                        placeholder="이름"
                        value={authForm.name}
                        onChange={(e) => setAuthForm({ ...authForm, name: e.target.value })}
                className="w-full pl-12 pr-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 transition-all"
                />
            </div>
            )}

            <div className="relative">
                <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-purple-300" />
                <input
                        type="email"
                        placeholder="이메일"
                        value={authForm.email}
                        onChange={(e) => setAuthForm({ ...authForm, email: e.target.value })}
                className="w-full pl-12 pr-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 transition-all"
                />
            </div>

            <div className="relative">
                <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-purple-300" />
                <input
                        type="password"
                        placeholder="비밀번호"
                        value={authForm.password}
                        onChange={(e) => setAuthForm({ ...authForm, password: e.target.value })}
                onKeyPress={(e) => e.key === 'Enter' && handleAuth()}
                className="w-full pl-12 pr-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 transition-all"
                />
            </div>

            <button
                    onClick={handleAuth}
                    className="w-full py-3 px-6 rounded-xl bg-gradient-to-r from-purple-500 to-pink-500 text-white font-semibold hover:shadow-lg hover:shadow-purple-500/50 transition-all duration-300 flex items-center justify-center gap-2"
            >
                <Sparkles className="w-5 h-5" />
                {authMode === 'login' ? '로그인' : '회원가입'}
            </button>
        </div>
    </div>
</div>
</div>
);
}

// Main Diary Screen
return (
<div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 p-6">
    {/* Prismatic Background Effects */}
    <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/4 w-96 h-96 bg-gradient-to-br from-purple-500/20 to-pink-500/20 rounded-full blur-3xl animate-pulse"></div>
        <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-gradient-to-br from-cyan-500/20 to-blue-500/20 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }}></div>
    <div className="absolute top-1/2 left-1/2 w-96 h-96 bg-gradient-to-br from-yellow-500/10 to-orange-500/10 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '2s' }}></div>
</div>

<div className="max-w-4xl mx-auto relative z-10">
    {/* Header with User Info */}
    <div className="flex items-center justify-between mb-8">
        <div className="text-center flex-1">
            <div className="inline-flex items-center gap-3 mb-2">
                <BookOpen className="w-10 h-10" strokeWidth={1.5} stroke="url(#gradient2)" />
                <svg width="0" height="0">
                    <defs>
                        <linearGradient id="gradient2" x1="0%" y1="0%" x2="100%" y2="100%">
                            <stop offset="0%" stopColor="#a855f7" />
                            <stop offset="50%" stopColor="#ec4899" />
                            <stop offset="100%" stopColor="#f59e0b" />
                        </linearGradient>
                    </defs>
                </svg>
                <h1 className="text-4xl font-bold bg-gradient-to-r from-purple-400 via-pink-400 to-yellow-400 bg-clip-text text-transparent">
                    My Prismatic Diary
                </h1>
            </div>
            <p className="text-purple-200/80">매일의 순간을 빛나는 기억으로</p>
        </div>

        <div className="flex items-center gap-3 p-3 rounded-2xl bg-white/5 backdrop-blur-xl border border-white/10">
            <div className="flex items-center gap-2 px-3">
                <User className="w-5 h-5 text-purple-300" />
                <span className="text-purple-100 font-medium">{user.name}</span>
            </div>
            <button
                    onClick={handleLogout}
                    className="p-2 rounded-lg bg-white/5 hover:bg-white/10 text-purple-300 hover:text-white transition-all"
                    title="로그아웃"
            >
                <LogOut className="w-5 h-5" />
            </button>
        </div>
    </div>

    {/* New Entry Button */}
    {!showEditor && (
    <button
            onClick={() => setShowEditor(true)}
    className="w-full mb-8 p-6 rounded-2xl bg-gradient-to-r from-purple-500/20 via-pink-500/20 to-yellow-500/20 backdrop-blur-xl border border-white/20 hover:border-white/40 transition-all duration-300 group"
    >
    <div className="flex items-center justify-center gap-3">
        <Plus className="w-6 h-6 text-purple-300 group-hover:rotate-90 transition-transform duration-300" />
        <span className="text-xl font-semibold text-white">새로운 일기 작성하기</span>
        <Sparkles className="w-5 h-5 text-yellow-300 group-hover:scale-110 transition-transform duration-300" />
    </div>
    </button>
    )}

    {/* Search Bar */}
    {!showEditor && entries.length > 0 && (
    <div className="mb-8 relative">
        <div className="relative">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-purple-300" />
            <input
                    type="text"
                    placeholder="일기 검색하기... (제목, 내용, 날짜)"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-12 pr-12 py-4 bg-white/5 backdrop-blur-xl border border-white/20 rounded-2xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 focus:shadow-lg focus:shadow-purple-500/20 transition-all duration-300"
            />
            {searchQuery && (
            <button
                    onClick={() => setSearchQuery('')}
            className="absolute right-4 top-1/2 -translate-y-1/2 text-purple-300 hover:text-white transition-colors"
            >
            <X className="w-5 h-5" />
            </button>
            )}
        </div>
        {searchQuery && (
        <p className="mt-2 text-purple-300/70 text-sm">
            {filteredEntries.length}개의 일기를 찾았습니다
        </p>
        )}
    </div>
    )}

    {/* Editor */}
    {showEditor && (
    <div className="mb-8 p-8 rounded-3xl bg-gradient-to-br from-purple-500/10 via-pink-500/10 to-yellow-500/10 backdrop-blur-2xl border border-white/20 shadow-2xl">
        <div className="flex items-center gap-2 mb-6">
            <Calendar className="w-5 h-5 text-purple-300" />
            <span className="text-purple-200">{new Date().toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' })}</span>
        </div>

        <input
                type="text"
                placeholder="제목을 입력하세요..."
                value={currentEntry.title}
                onChange={(e) => setCurrentEntry({ ...currentEntry, title: e.target.value })}
        className="w-full mb-4 p-4 bg-white/5 border border-white/10 rounded-xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 text-xl font-semibold"
        />

        <div className="mb-4 flex gap-2">
            {Object.keys(moodColors).map((mood) => (
            <button
                    key={mood}
                    onClick={() => setCurrentEntry({ ...currentEntry, mood })}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300 ${
            currentEntry.mood === mood
            ? `bg-gradient-to-r ${moodColors[mood]} text-white shadow-lg scale-105`
            : 'bg-white/5 text-purple-200 hover:bg-white/10'
            }`}
            >
            {mood === 'happy' ? '😊 행복' : mood === 'neutral' ? '😌 평온' : mood === 'sad' ? '😢 우울' : '🤩 신남'}
            </button>
            ))}
        </div>

        <textarea
                placeholder="오늘의 이야기를 들려주세요..."
                value={currentEntry.content}
                onChange={(e) => setCurrentEntry({ ...currentEntry, content: e.target.value })}
        className="w-full h-64 p-4 bg-white/5 border border-white/10 rounded-xl text-white placeholder-purple-300/50 focus:outline-none focus:border-purple-400/50 resize-none"
        />

        <div className="flex gap-3 mt-6">
        <button
        onClick={handleSave}
        className="flex-1 py-3 px-6 rounded-xl bg-gradient-to-r from-purple-500 to-pink-500 text-white font-semibold hover:shadow-lg hover:shadow-purple-500/50 transition-all duration-300"
        >
        저장하기
        </button>
        <button
        onClick={() => {
        setShowEditor(false);
        setCurrentEntry({ title: '', content: '', mood: 'neutral' });
        }}
        className="px-6 py-3 rounded-xl bg-white/5 text-purple-200 hover:bg-white/10 transition-all duration-300"
        >
        취소
        </button>
        </div>
        </div>
        )}

        {/* Entries List */}
        <div className="space-y-6">
        {filteredEntries.map((entry, index) => (
        <div
        key={entry.id}
        className="group p-6 rounded-2xl bg-gradient-to-br from-white/5 to-white/10 backdrop-blur-xl border border-white/10 hover:border-white/30 transition-all duration-300 hover:shadow-2xl hover:shadow-purple-500/20"
        style={{ animationDelay: `${index * 0.1}s` }}
        >
        <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
        <div className={`w-3 h-3 rounded-full bg-gradient-to-r ${moodColors[entry.mood]} shadow-lg`}></div>
        <h3 className="text-2xl font-bold text-white">{highlightText(entry.title, searchQuery)}</h3>
        </div>
        <span className="text-purple-300/70 text-sm">{highlightText(new Date(entry.date).toLocaleDateString('ko-KR'), searchQuery)}</span>
        </div>
        <p className="text-purple-100/80 leading-relaxed whitespace-pre-wrap">{highlightText(entry.content, searchQuery)}</p>

        {/* Prismatic border effect on hover */}
        <div className={`absolute inset-0 rounded-2xl bg-gradient-to-r ${moodColors[entry.mood]} opacity-0 group-hover:opacity-20 transition-opacity duration-300 -z-10 blur-xl`}></div>
        </div>
        ))}
        </div>

        {filteredEntries.length === 0 && searchQuery && (
        <div className="text-center py-20">
        <Search className="w-16 h-16 mx-auto mb-4 text-purple-400/50" />
        <p className="text-purple-300/60 text-lg">검색 결과가 없습니다</p>
        <p className="text-purple-400/40 text-sm mt-2">다른 키워드로 검색해보세요</p>
        </div>
        )}

        {entries.length === 0 && !showEditor && (
        <div className="text-center py-20">
        <Sparkles className="w-16 h-16 mx-auto mb-4 text-purple-400/50" />
        <p className="text-purple-300/60 text-lg">아직 작성된 일기가 없습니다</p>
        <p className="text-purple-400/40 text-sm mt-2">첫 번째 일기를 작성해보세요!</p>
        </div>
        )}
        </div>
        </div>
        );
        }