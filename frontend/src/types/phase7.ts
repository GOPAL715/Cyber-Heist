/** Matches the backend `AchievementCategory` enum. */
export type AchievementCategory =
  | 'MISSIONS'
  | 'PUZZLES'
  | 'PROGRESSION'
  | 'ECONOMY'
  | 'EQUIPMENT'
  | 'SKILLS'
  | 'BOSSES'
  | 'DAILY';

/** A step in the permanent milestone gallery. */
export interface Achievement {
  code: string;
  name: string;
  description: string;
  category: AchievementCategory;
  /** Measured against the requirement. A missing row is zero progress. */
  progress: number;
  requirement: number;
  percentComplete: number;
  unlocked: boolean;
  unlockedAt: string | null;
  icon: string;
  reward: Reward;
}

/** One objective the server drew for today. */
export interface DailyChallenge {
  code: string;
  title: string;
  description: string;
  requirementType: string;
  progress: number;
  requirement: number;
  percentComplete: number;
  completed: boolean;
  completedAt: string | null;
  reward: Reward;
}

/** What the daily page returns: date, the three objectives, and the streak. */
export interface DailyOverview {
  date: string;
  challenges: DailyChallenge[];
  streak: StreakView;
}

/** The streak the server reports: current and longest consecutive days. */
export interface StreakView {
  current: number;
  longest: number;
}

/** A payout decided entirely on the server. */
export interface Reward {
  experience: number;
  coins: number;
}
